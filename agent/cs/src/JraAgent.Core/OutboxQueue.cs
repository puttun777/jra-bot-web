using Microsoft.Data.Sqlite;

namespace JraAgent.Core;

public sealed record BatchRow(
    long Id,
    string BatchId,
    string Cls,
    string Dataspec,
    string? FetchId,
    long? FetchSeq,
    bool Eof,
    int Priority,
    double ObservedAt,
    double? ExpiresAt,
    byte[] Payload,
    string PayloadSha256,
    int RecordCount,
    string Status,
    int Attempts,
    double NextTryAt,
    string? LastOutcome,
    string? DeadReason)
{
    /// <summary>本文を出さない。</summary>
    public override string ToString() => $"BatchRow(id={Id}, batch_id={BatchId}, {Cls}/{Dataspec}, status={Status}, attempts={Attempts}, bytes={Payload.Length})";
}

public sealed record SessionRow(
    string FetchId,
    string CursorKey,
    string Dataspec,
    long? Option,
    string Origin,
    long? BackfillId,
    string? Fromtime,
    string? LastFileTimestamp,
    string? TargetFrom,
    string? TargetTo,
    string State,
    long NextSeq,
    bool CursorConfirmed,
    string? Reason);

public sealed record Checkpoint(string CursorKey, string? FetchedTs, string? QueuedTs, long? QueuedSeq, string? AckedTs);

/// <summary>200 応答から取り出した ACK（サーバの IngestAck）。<c>Cursor*</c> はサーバのカーソルが進んだ（確認できた）ときだけ。</summary>
public sealed record AckInfo(
    int Accepted = 0,
    int Duplicates = 0,
    int Quarantined = 0,
    int Unsupported = 0,
    bool Duplicate = false,
    string? FetchId = null,
    string? FetchStatus = null, // IN_PROGRESS / SUCCEEDED / FAILED
    long? FetchNextSeq = null,
    string? CursorType = null,
    string? CursorTs = null);

public sealed record QueueStats(IReadOnlyDictionary<string, int> ByStatus, long PendingBytes, double? OldestQueuedAt, int RealtimeDropped, int Dead, long? DiskFreeBytes);

/// <summary>
/// SQLite WAL の outbox・チェックポイント・取得セッション（旧 queue/store.py の移植。スキーマ・状態の意味は同じで、旧版のキューファイルをそのまま開ける）。
/// 不変条件（試験と <see cref="Verify"/> で検査する）:
/// 1. バッチは commit された時点で永続。プロセスが落ちても、起動時に SENDING を QUEUED へ戻して同じ batchId・同じ内容で再送する。
/// 2. <c>acked_ts</c> は EOF バッチの ACK にサーバの cursor が付いたときにだけ前進し、後戻りしない（LIVE の取得だけ。BACKFILL は動かさない）。
/// 3. 取得セッションの連番（fetch_seq）は 0 から連続し、再送でも変えない。
/// 4. 同時に SENDING なのは 1 つだけ（順序保持）。取得セッション内は、手前が ACKED になるまで次を送らない（サーバは先走りを 409 で断る）。
/// 5. 容量の上限・ディスク不足・期限切れ速報は DEAD / 取り込み拒否になり、成功には数えない。ログに本文を出さない。
/// </summary>
[System.Diagnostics.CodeAnalysis.SuppressMessage("Naming", "CA1711", Justification = "旧版と同じ名前（設計文書・運用手順が OutboxQueue で参照する）")]
public sealed class OutboxQueue : IDisposable
{
    public const int SchemaVersion = 1;
    public const int PriorityRealtime = 0; // 速報（二度と取れない）を先に送る
    public const int PriorityStored = 1;

    private const string Ddl = """
        CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);

        CREATE TABLE IF NOT EXISTS fetch_session (
          fetch_id        TEXT PRIMARY KEY,
          cursor_key      TEXT NOT NULL,
          dataspec        TEXT NOT NULL,
          option          INTEGER,
          origin          TEXT NOT NULL CHECK (origin IN ('LIVE', 'BACKFILL')),
          backfill_id     INTEGER,
          fromtime        TEXT,
          last_file_timestamp TEXT,
          target_from     TEXT,
          target_to       TEXT,
          state           TEXT NOT NULL CHECK (state IN ('READING', 'FETCHED', 'COMPLETED', 'ABORTED', 'INTERRUPTED')),
          next_seq        INTEGER NOT NULL DEFAULT 0,
          cursor_confirmed INTEGER NOT NULL DEFAULT 0,
          reason          TEXT,
          created_at      REAL NOT NULL,
          updated_at      REAL NOT NULL,
          completed_at    REAL
        );

        CREATE TABLE IF NOT EXISTS batch (
          id              INTEGER PRIMARY KEY AUTOINCREMENT,
          batch_id        TEXT NOT NULL UNIQUE,
          cls             TEXT NOT NULL CHECK (cls IN ('S', 'R')),
          dataspec        TEXT NOT NULL,
          fetch_id        TEXT REFERENCES fetch_session (fetch_id),
          fetch_seq       INTEGER,
          eof             INTEGER NOT NULL,
          priority        INTEGER NOT NULL,
          observed_at     REAL NOT NULL,
          expires_at      REAL,
          payload         BLOB NOT NULL,
          payload_sha256  TEXT NOT NULL,
          payload_len     INTEGER NOT NULL,
          record_count    INTEGER NOT NULL,
          status          TEXT NOT NULL CHECK (status IN ('QUEUED', 'SENDING', 'RETRY_WAIT', 'ACKED', 'DEAD')),
          attempts        INTEGER NOT NULL DEFAULT 0,
          next_try_at     REAL NOT NULL DEFAULT 0,
          last_outcome    TEXT,
          dead_reason     TEXT,
          created_at      REAL NOT NULL,
          updated_at      REAL NOT NULL,
          acked_at        REAL,
          UNIQUE (fetch_id, fetch_seq),
          CHECK ((fetch_id IS NULL) = (fetch_seq IS NULL))
        );
        CREATE INDEX IF NOT EXISTS batch_by_status ON batch (status, priority, id);
        CREATE INDEX IF NOT EXISTS batch_by_fetch ON batch (fetch_id, fetch_seq);

        CREATE TABLE IF NOT EXISTS checkpoint (
          cursor_key  TEXT PRIMARY KEY,
          fetched_ts  TEXT,
          queued_ts   TEXT,
          queued_seq  INTEGER,
          acked_ts    TEXT,
          updated_at  REAL NOT NULL
        );

        CREATE TABLE IF NOT EXISTS state (k TEXT PRIMARY KEY, v TEXT NOT NULL, updated_at REAL NOT NULL);

        CREATE TABLE IF NOT EXISTS alert (
          name      TEXT PRIMARY KEY,
          detail    TEXT NOT NULL,
          raised_at REAL NOT NULL
        );
        """;

    private const string ExpireSql =
        "UPDATE batch SET status = 'DEAD', dead_reason = 'EXPIRED', payload = x'', updated_at = $now "
        + "WHERE cls = 'R' AND expires_at IS NOT NULL AND expires_at <= $now AND status IN ('QUEUED','RETRY_WAIT')";

    private readonly string path;
    private readonly IClock clock;
    private QueueConfig cfg;
    private readonly Func<string, long> diskFree;
    private readonly SqliteConnection db;
    private readonly Lock gate = new();

    public OutboxQueue(string path, IClock clock, QueueConfig? cfg = null, Func<string, long>? diskFree = null, bool readOnly = false)
    {
        this.path = Path.GetFullPath(path);
        this.clock = clock;
        this.cfg = cfg ?? new QueueConfig();
        this.diskFree = diskFree ?? (p => new DriveInfo(p).AvailableFreeSpace);
        var dir = Path.GetDirectoryName(this.path) ?? throw new QueueException("キューの場所が不正");
        if (!readOnly) Directory.CreateDirectory(dir);
        var cs = new SqliteConnectionStringBuilder
        {
            DataSource = this.path,
            Mode = readOnly ? SqliteOpenMode.ReadOnly : SqliteOpenMode.ReadWriteCreate,
            Pooling = false,
            DefaultTimeout = 30,
        };
        db = new SqliteConnection(cs.ToString());
        try
        {
            db.Open();
            Exec("PRAGMA busy_timeout = 30000");
            if (!readOnly)
            {
                var mode = Scalar("PRAGMA journal_mode = WAL")?.ToString();
                if (!string.Equals(mode, "wal", StringComparison.OrdinalIgnoreCase)) throw new QueueException("WAL モードにできない（ネットワークドライブなどでは使えない）");
                Exec("PRAGMA synchronous = FULL"); // 電源断でも commit 済みを失わない
                Exec("PRAGMA foreign_keys = ON");
                Migrate();
            }
        }
        catch (SqliteException)
        {
            db.Dispose();
            throw new QueueException("キューを開けない");
        }
        catch
        {
            db.Dispose();
            throw;
        }
    }

    /// <summary>試験用: 上限を後から変える（旧試験の <c>q._cfg = ...</c>）。</summary>
    internal QueueConfig Cfg
    {
        get => cfg;
        set => cfg = value;
    }

    /// <summary>試験用: 生の SQL を実行する（壊れた状態・容量不足の再現）。</summary>
    internal void ExecRaw(string sql)
    {
        lock (gate) Exec(sql);
    }

    public void Dispose()
    {
        lock (gate) db.Dispose();
    }

    // ------------------------------------------------------------------ 基盤
    private SqliteCommand Cmd(string sql, params (string Name, object? Value)[] args)
    {
        var c = db.CreateCommand();
        c.CommandText = sql;
        foreach (var (n, v) in args) c.Parameters.AddWithValue(n, v ?? DBNull.Value);
        return c;
    }

    private int Exec(string sql, params (string Name, object? Value)[] args)
    {
        using var c = Cmd(sql, args);
        return c.ExecuteNonQuery();
    }

    private object? Scalar(string sql, params (string Name, object? Value)[] args)
    {
        using var c = Cmd(sql, args);
        var v = c.ExecuteScalar();
        return v is DBNull ? null : v;
    }

    private List<T> Query<T>(string sql, Func<SqliteDataReader, T> map, params (string Name, object? Value)[] args)
    {
        using var c = Cmd(sql, args);
        using var r = c.ExecuteReader();
        var list = new List<T>();
        while (r.Read()) list.Add(map(r));
        return list;
    }

    private void Migrate()
    {
        Tx(() =>
        {
            Exec(Ddl);
            var v = Scalar("SELECT v FROM meta WHERE k = 'schema_version'");
            if (v is null) Exec("INSERT INTO meta (k, v) VALUES ('schema_version', $v)", ("$v", SchemaVersion.ToString(System.Globalization.CultureInfo.InvariantCulture)));
            else if (v.ToString() != SchemaVersion.ToString(System.Globalization.CultureInfo.InvariantCulture)) throw new QueueException("キューのスキーマ版が違う");
            return 0;
        });
    }

    /// <summary>SQLITE_FULL（13）と SQLITE_IOERR（10）を容量不足として扱う（旧版はメッセージの文字列で判定していた）。</summary>
    private static bool IsFull(SqliteException e) => e.SqliteErrorCode is 13 or 10;

    private void Rollback()
    {
        try
        {
            Exec("ROLLBACK");
        }
        catch (SqliteException)
        {
            // すでに自動で巻き戻っている（容量不足など）
        }
    }

    private T Tx<T>(Func<T> body)
    {
        lock (gate)
        {
            try
            {
                Exec("BEGIN IMMEDIATE");
            }
            catch (SqliteException)
            {
                throw new QueueException("キューを開始できない");
            }
            T result;
            try
            {
                result = body();
            }
            catch (SqliteException e)
            {
                Rollback();
                if (IsFull(e)) throw new QueueFullException("DISK_FULL");
                throw new QueueException("キューの操作に失敗");
            }
            catch
            {
                Rollback();
                throw;
            }
            try
            {
                Exec("COMMIT");
            }
            catch (SqliteException e)
            {
                Rollback();
                if (IsFull(e)) throw new QueueFullException("DISK_FULL");
                throw new QueueException("commit に失敗");
            }
            return result;
        }
    }

    private double Now() => clock.Now.ToUnixTimeMilliseconds() / 1000.0;

    private static string? S(SqliteDataReader r, string c) => r.IsDBNull(r.GetOrdinal(c)) ? null : r.GetString(r.GetOrdinal(c));

    private static long? L(SqliteDataReader r, string c) => r.IsDBNull(r.GetOrdinal(c)) ? null : r.GetInt64(r.GetOrdinal(c));

    private static double? D(SqliteDataReader r, string c) => r.IsDBNull(r.GetOrdinal(c)) ? null : r.GetDouble(r.GetOrdinal(c));

    private static BatchRow MapBatch(SqliteDataReader r) => new(
        r.GetInt64(r.GetOrdinal("id")),
        r.GetString(r.GetOrdinal("batch_id")),
        r.GetString(r.GetOrdinal("cls")),
        r.GetString(r.GetOrdinal("dataspec")),
        S(r, "fetch_id"),
        L(r, "fetch_seq"),
        r.GetInt64(r.GetOrdinal("eof")) != 0,
        r.GetInt32(r.GetOrdinal("priority")),
        r.GetDouble(r.GetOrdinal("observed_at")),
        D(r, "expires_at"),
        (byte[])r["payload"],
        r.GetString(r.GetOrdinal("payload_sha256")),
        r.GetInt32(r.GetOrdinal("record_count")),
        r.GetString(r.GetOrdinal("status")),
        r.GetInt32(r.GetOrdinal("attempts")),
        r.GetDouble(r.GetOrdinal("next_try_at")),
        S(r, "last_outcome"),
        S(r, "dead_reason"));

    private static SessionRow MapSession(SqliteDataReader r) => new(
        r.GetString(r.GetOrdinal("fetch_id")),
        r.GetString(r.GetOrdinal("cursor_key")),
        r.GetString(r.GetOrdinal("dataspec")),
        L(r, "option"),
        r.GetString(r.GetOrdinal("origin")),
        L(r, "backfill_id"),
        S(r, "fromtime"),
        S(r, "last_file_timestamp"),
        S(r, "target_from"),
        S(r, "target_to"),
        r.GetString(r.GetOrdinal("state")),
        r.GetInt64(r.GetOrdinal("next_seq")),
        r.GetInt64(r.GetOrdinal("cursor_confirmed")) != 0,
        S(r, "reason"));

    private static Checkpoint MapCheckpoint(SqliteDataReader r) =>
        new(r.GetString(r.GetOrdinal("cursor_key")), S(r, "fetched_ts"), S(r, "queued_ts"), L(r, "queued_seq"), S(r, "acked_ts"));

    private SessionRow? SessionNoLock(string fetchId) =>
        Query("SELECT * FROM fetch_session WHERE fetch_id = $f", MapSession, ("$f", fetchId)).FirstOrDefault();

    private BatchRow? BatchNoLock(string batchId) =>
        Query("SELECT * FROM batch WHERE batch_id = $b", MapBatch, ("$b", batchId)).FirstOrDefault();

    // ------------------------------------------------------------------ 取得セッション
    public SessionRow BeginSession(
        string fetchId,
        string cursorKey,
        string dataspec,
        long? option,
        string? fromtime,
        string? lastFileTimestamp,
        string? targetFrom = null,
        string? targetTo = null,
        string origin = "LIVE",
        long? backfillId = null)
    {
        var now = Now();
        Tx(() => Exec(
            "INSERT INTO fetch_session (fetch_id, cursor_key, dataspec, option, origin, backfill_id, fromtime, last_file_timestamp, target_from, target_to,"
            + " state, next_seq, created_at, updated_at) VALUES ($f,$k,$d,$o,$origin,$bf,$from,$lft,$tf,$tt, 'READING', 0, $now, $now)",
            ("$f", fetchId), ("$k", cursorKey), ("$d", dataspec), ("$o", option), ("$origin", origin), ("$bf", backfillId),
            ("$from", fromtime), ("$lft", lastFileTimestamp), ("$tf", targetFrom), ("$tt", targetTo), ("$now", now)));
        return Session(fetchId) ?? throw new QueueException("取得セッションを作れない");
    }

    public SessionRow? Session(string fetchId)
    {
        lock (gate) return SessionNoLock(fetchId);
    }

    public IReadOnlyList<SessionRow> Sessions(params string[] states)
    {
        lock (gate)
        {
            var rows = Query("SELECT * FROM fetch_session ORDER BY created_at, fetch_id", MapSession);
            return states.Length == 0 ? rows : [.. rows.Where(r => states.Contains(r.State))];
        }
    }

    /// <summary>同じ種別の取得が重複して起動しない（READING のセッションがあれば新しく始めない）。</summary>
    public bool HasOpenSession(string cursorKey)
    {
        lock (gate) return Scalar("SELECT 1 FROM fetch_session WHERE cursor_key = $k AND state = 'READING' LIMIT 1", ("$k", cursorKey)) is not null;
    }

    /// <summary>セッションを中止する。まだ送っていないバッチは DEAD にする（サーバは先走りも終了後の追加も受け付けない）。<c>acked_ts</c> は動かさない。</summary>
    public void AbortSession(string fetchId, string reason, string state = "ABORTED")
    {
        var now = Now();
        Tx(() =>
        {
            AbortNoTx(fetchId, reason, now, state);
            return 0;
        });
    }

    private static string Cut(string s, int n) => s.Length <= n ? s : s[..n];

    private void AbortNoTx(string fetchId, string reason, double now, string state = "ABORTED")
    {
        Exec(
            "UPDATE fetch_session SET state = $s, reason = $r, updated_at = $now, completed_at = $now WHERE fetch_id = $f AND state IN ('READING','FETCHED')",
            ("$s", state), ("$r", Cut(reason, 100)), ("$now", now), ("$f", fetchId));
        Exec(
            "UPDATE batch SET status = 'DEAD', dead_reason = 'SESSION_ABORTED', payload = x'', updated_at = $now WHERE fetch_id = $f AND status IN ('QUEUED','RETRY_WAIT')",
            ("$now", now), ("$f", fetchId));
    }

    // ------------------------------------------------------------------ 投入
    private long PendingBytesNoLock() => Convert.ToInt64(Scalar("SELECT COALESCE(SUM(payload_len), 0) FROM batch WHERE status IN ('QUEUED','SENDING','RETRY_WAIT')") ?? 0L, System.Globalization.CultureInfo.InvariantCulture);

    /// <summary>
    /// バッチを永続化する。1 つのトランザクションで、行の挿入・セッションの連番・チェックポイント（EOF のとき）を更新する。
    /// 上限・ディスク不足のときは <see cref="QueueFullException"/>（何も書かない）。速報（R）は古いものを DEAD にして場所を空ける。蓄積系（S）は捨てない。
    /// </summary>
    public BatchRow Enqueue(
        string batchId,
        string cls,
        string dataspec,
        byte[] payload,
        int recordCount,
        bool eof,
        string? fetchId = null,
        double? observedAt = null,
        string? lastFileTimestamp = null)
    {
        if (cls is not ("S" or "R") || (cls == "R" && fetchId is not null)) throw new QueueException("クラスと取得セッションの組合せが不正");
        var now = Now();
        var obs = observedAt ?? now;
        var priority = cls == "R" ? PriorityRealtime : PriorityStored;
        double? expires = cls == "R" ? obs + cfg.RealtimeTtlSeconds : null;
        var free = FreeBytes();
        if (free is { } f && f - payload.Length < cfg.MinFreeBytes) throw new QueueFullException("DISK_LOW"); // ディスク容量が少ないときは安全に停止する
        return Tx(() =>
        {
            var used = PendingBytesNoLock();
            if (used + payload.Length > cfg.MaxBytes)
            {
                if (cls == "R")
                {
                    DropOldestRealtime(used + payload.Length - cfg.MaxBytes, now);
                    used = PendingBytesNoLock();
                }
                if (used + payload.Length > cfg.MaxBytes) throw new QueueFullException("QUEUE_FULL");
            }
            long? seq = null;
            if (fetchId is not null)
            {
                var s = SessionNoLock(fetchId);
                if (s is null || s.State != "READING") throw new QueueException("取得セッションが取得中ではない");
                seq = s.NextSeq;
            }
            Exec(
                "INSERT INTO batch (batch_id, cls, dataspec, fetch_id, fetch_seq, eof, priority, observed_at, expires_at, payload, payload_sha256,"
                + " payload_len, record_count, status, created_at, updated_at) VALUES ($b,$c,$d,$f,$seq,$eof,$p,$obs,$exp,$payload,$sha,$len,$n, 'QUEUED', $now, $now)",
                ("$b", batchId), ("$c", cls), ("$d", dataspec), ("$f", fetchId), ("$seq", seq), ("$eof", eof ? 1 : 0), ("$p", priority), ("$obs", obs),
                ("$exp", expires), ("$payload", payload), ("$sha", Signing.Sha256Hex(payload)), ("$len", payload.Length), ("$n", recordCount), ("$now", now));
            var bid = Convert.ToInt64(Scalar("SELECT id FROM batch WHERE batch_id = $b", ("$b", batchId)), System.Globalization.CultureInfo.InvariantCulture);
            if (fetchId is not null)
            {
                Exec(
                    "UPDATE fetch_session SET next_seq = next_seq + 1, last_file_timestamp = COALESCE($lft, last_file_timestamp), updated_at = $now WHERE fetch_id = $f",
                    ("$lft", lastFileTimestamp), ("$now", now), ("$f", fetchId));
                var s = SessionNoLock(fetchId) ?? throw new QueueException("取得セッションが消えた");
                BumpCheckpoint(s.CursorKey, now, queuedSeq: bid);
                if (eof)
                {
                    // EOF までの全バッチがキューに入った = 取得済み。fetched_ts と queued_ts はこの 1 トランザクションで動く（acked_ts は動かない）
                    Exec("UPDATE fetch_session SET state = 'FETCHED', updated_at = $now WHERE fetch_id = $f", ("$now", now), ("$f", fetchId));
                    if (!string.IsNullOrEmpty(s.LastFileTimestamp) && s.Origin == "LIVE")
                        BumpCheckpoint(s.CursorKey, now, fetchedTs: s.LastFileTimestamp, queuedTs: s.LastFileTimestamp);
                }
            }
            return Query("SELECT * FROM batch WHERE id = $id", MapBatch, ("$id", bid))[0];
        });
    }

    private long? FreeBytes()
    {
        try
        {
            return diskFree(Path.GetDirectoryName(path) ?? path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return null;
        }
    }

    private void DropOldestRealtime(long need, double now)
    {
        long freed = 0;
        var rows = Query(
            "SELECT id, payload_len FROM batch WHERE cls = 'R' AND status IN ('QUEUED','RETRY_WAIT') ORDER BY observed_at, id",
            r => (Id: r.GetInt64(0), Len: r.GetInt64(1)));
        foreach (var (id, len) in rows)
        {
            if (freed >= need) break;
            Exec("UPDATE batch SET status = 'DEAD', dead_reason = 'OVERFLOW_DROPPED', payload = x'', updated_at = $now WHERE id = $id", ("$now", now), ("$id", id));
            freed += len;
        }
    }

    /// <summary>チェックポイントは前進だけ（文字列比較。14 桁の数字なので辞書順 = 時刻順）。</summary>
    private void BumpCheckpoint(string key, double now, string? fetchedTs = null, string? queuedTs = null, long? queuedSeq = null, string? ackedTs = null)
    {
        Exec("INSERT OR IGNORE INTO checkpoint (cursor_key, updated_at) VALUES ($k, $now)", ("$k", key), ("$now", now));
        // 列名は固定の 4 つだけ（外部入力を SQL に入れない）
        if (fetchedTs is not null) Exec("UPDATE checkpoint SET fetched_ts = $v, updated_at = $now WHERE cursor_key = $k AND (fetched_ts IS NULL OR fetched_ts < $v)", ("$v", fetchedTs), ("$now", now), ("$k", key));
        if (queuedTs is not null) Exec("UPDATE checkpoint SET queued_ts = $v, updated_at = $now WHERE cursor_key = $k AND (queued_ts IS NULL OR queued_ts < $v)", ("$v", queuedTs), ("$now", now), ("$k", key));
        if (queuedSeq is not null) Exec("UPDATE checkpoint SET queued_seq = $v, updated_at = $now WHERE cursor_key = $k AND (queued_seq IS NULL OR queued_seq < $v)", ("$v", queuedSeq), ("$now", now), ("$k", key));
        if (ackedTs is not null) Exec("UPDATE checkpoint SET acked_ts = $v, updated_at = $now WHERE cursor_key = $k AND (acked_ts IS NULL OR acked_ts < $v)", ("$v", ackedTs), ("$now", now), ("$k", key));
    }

    // ------------------------------------------------------------------ 送信側
    /// <summary>
    /// 起動時の復旧。SENDING（送信中に落ちた。サーバに届いたかは不明）→ QUEUED、取得中のまま残ったセッション → INTERRUPTED。
    /// バッチは捨てず、同じ batchId・同じ内容で再送する（サーバは重複 ACK で吸収）。
    /// </summary>
    public (int SendingRequeued, int SessionsInterrupted) Recover()
    {
        var now = Now();
        return Tx(() => (
            Exec("UPDATE batch SET status = 'QUEUED', next_try_at = 0, updated_at = $now WHERE status = 'SENDING'", ("$now", now)),
            Exec("UPDATE fetch_session SET state = 'INTERRUPTED', reason = 'RESTART', updated_at = $now, completed_at = $now WHERE state = 'READING'", ("$now", now))));
    }

    public int ExpireRealtime()
    {
        var now = Now();
        return Tx(() => Exec(ExpireSql, ("$now", now)));
    }

    /// <summary>次に送るバッチを 1 つだけ取り出して SENDING にする（原子的）。送信中のものがあれば null（順序を保つ）。</summary>
    public BatchRow? ClaimNext()
    {
        var now = Now();
        return Tx(() =>
        {
            if (Scalar("SELECT 1 FROM batch WHERE status = 'SENDING' LIMIT 1") is not null) return null;
            Exec(ExpireSql, ("$now", now));
            var row = Query(
                "SELECT b.* FROM batch b WHERE b.status IN ('QUEUED','RETRY_WAIT') AND b.next_try_at <= $now "
                + " AND (b.fetch_id IS NULL OR NOT EXISTS (SELECT 1 FROM batch p WHERE p.fetch_id = b.fetch_id AND p.fetch_seq < b.fetch_seq AND p.status <> 'ACKED')) "
                + "ORDER BY b.priority, b.id LIMIT 1",
                MapBatch,
                ("$now", now)).FirstOrDefault();
            if (row is null) return null;
            Exec("UPDATE batch SET status = 'SENDING', attempts = attempts + 1, updated_at = $now WHERE id = $id", ("$now", now), ("$id", row.Id));
            return Query("SELECT * FROM batch WHERE id = $id", MapBatch, ("$id", row.Id))[0];
        });
    }

    /// <summary>次に送れる時刻（待機中のものだけ）。null なら送るものが無い。</summary>
    public double? NextEligibleAt()
    {
        lock (gate)
        {
            var v = Scalar("SELECT MIN(next_try_at) FROM batch WHERE status IN ('QUEUED','RETRY_WAIT')");
            return v is null ? null : Convert.ToDouble(v, System.Globalization.CultureInfo.InvariantCulture);
        }
    }

    /// <summary>送れなかった（モード待機など）。試行回数を戻し、そのまま QUEUED に戻す（待機は試行に数えない）。</summary>
    public void Defer(string batchId, string outcome)
    {
        var now = Now();
        Tx(() => Exec(
            "UPDATE batch SET status = 'QUEUED', attempts = MAX(attempts - 1, 0), last_outcome = $o, updated_at = $now WHERE batch_id = $b AND status = 'SENDING'",
            ("$o", Cut(outcome, 40)), ("$now", now), ("$b", batchId)));
    }

    /// <summary>同じバッチを後で再送する。試行回数が上限なら DEAD（RETRY_EXHAUSTED）にして取得セッションも中止する。戻り値は新しい状態。</summary>
    public string RetryLater(string batchId, double delay, string outcome, int maxAttempts)
    {
        var now = Now();
        return Tx(() =>
        {
            var r = BatchNoLock(batchId);
            if (r is null || r.Status != "SENDING") throw new QueueException("送信中でないバッチは再試行に回せない");
            if (r.Attempts >= maxAttempts)
            {
                Exec(
                    "UPDATE batch SET status = 'DEAD', dead_reason = 'RETRY_EXHAUSTED', last_outcome = $o, payload = x'', updated_at = $now WHERE id = $id",
                    ("$o", Cut(outcome, 40)), ("$now", now), ("$id", r.Id));
                if (!string.IsNullOrEmpty(r.FetchId)) AbortNoTx(r.FetchId, "RETRY_EXHAUSTED", now);
                return "DEAD";
            }
            Exec(
                "UPDATE batch SET status = 'RETRY_WAIT', next_try_at = $t, last_outcome = $o, updated_at = $now WHERE id = $id",
                ("$t", now + delay), ("$o", Cut(outcome, 40)), ("$now", now), ("$id", r.Id));
            return "RETRY_WAIT";
        });
    }

    /// <summary>不正データなど。自動再送を止める。取得セッションの一部なら、セッション全体を中止する（<c>acked_ts</c> は動かない）。</summary>
    public void MarkDead(string batchId, string reason)
    {
        var now = Now();
        Tx(() =>
        {
            var r = BatchNoLock(batchId);
            if (r is null || r.Status is not ("SENDING" or "QUEUED" or "RETRY_WAIT")) throw new QueueException("DEAD にできない状態");
            Exec(
                "UPDATE batch SET status = 'DEAD', dead_reason = $r, last_outcome = 'DEAD', updated_at = $now WHERE id = $id",
                ("$r", Cut(reason, 60)), ("$now", now), ("$id", r.Id));
            if (!string.IsNullOrEmpty(r.FetchId)) AbortNoTx(r.FetchId, Cut($"DEAD:{reason}", 100), now);
            return 0;
        });
    }

    /// <summary>サーバの ACK を反映する。<c>acked_ts</c> は EOF バッチの ACK に cursor が付き、種別が一致する LIVE のセッションのときだけ前進する。</summary>
    public void MarkAcked(string batchId, AckInfo ack)
    {
        var now = Now();
        Tx(() =>
        {
            var r = BatchNoLock(batchId) ?? throw new QueueException("不明なバッチ");
            if (r.Status == "ACKED") return 0; // 二重の反映をしない
            if (r.Status != "SENDING") throw new QueueException("送信中でないバッチの ACK");
            Exec(
                "UPDATE batch SET status = 'ACKED', payload = x'', acked_at = $now, updated_at = $now, last_outcome = 'OK' WHERE id = $id",
                ("$now", now), ("$id", r.Id));
            var fid = r.FetchId;
            if (string.IsNullOrEmpty(fid)) return 0;
            var s = SessionNoLock(fid);
            if (s is null) return 0;
            if (ack.FetchStatus == "FAILED")
            {
                AbortNoTx(fid, "SERVER_FETCH_FAILED", now); // 取得の事実を作れなかった。完了にせず、次の取得でやり直す
                return 0;
            }
            if (!r.Eof)
            {
                if (ack.FetchStatus == "SUCCEEDED") AbortNoTx(fid, "PROTOCOL_UNEXPECTED_SUCCESS", now); // 途中のバッチで成功になるのは契約違反
                return 0;
            }
            if (s.State is not ("FETCHED" or "READING")) return 0;
            var confirmed = ack.FetchStatus == "SUCCEEDED"
                && ack.CursorTs is not null
                && ack.CursorType == s.CursorKey
                && s.Origin == "LIVE"
                && s.LastFileTimestamp is not null
                && ack.CursorTs == s.LastFileTimestamp; // 自分の取得が確定させた値と一致するときだけ（ずれていれば進めない）
            Exec(
                "UPDATE fetch_session SET state = 'COMPLETED', cursor_confirmed = $c, updated_at = $now, completed_at = $now WHERE fetch_id = $f",
                ("$c", confirmed ? 1 : 0), ("$now", now), ("$f", fid));
            if (confirmed) BumpCheckpoint(s.CursorKey, now, ackedTs: ack.CursorTs);
            return 0;
        });
    }

    // ------------------------------------------------------------------ 参照
    public Checkpoint GetCheckpoint(string key)
    {
        lock (gate)
            return Query("SELECT * FROM checkpoint WHERE cursor_key = $k", MapCheckpoint, ("$k", key)).FirstOrDefault() ?? new Checkpoint(key, null, null, null, null);
    }

    public IReadOnlyList<Checkpoint> Checkpoints()
    {
        lock (gate) return Query("SELECT * FROM checkpoint ORDER BY cursor_key", MapCheckpoint);
    }

    public BatchRow? Batch(string batchId)
    {
        lock (gate) return BatchNoLock(batchId);
    }

    public IReadOnlyList<BatchRow> Batches(string? status = null)
    {
        lock (gate)
        {
            var rows = Query("SELECT * FROM batch ORDER BY id", MapBatch);
            return status is null ? rows : [.. rows.Where(r => r.Status == status)];
        }
    }

    public QueueStats Stats()
    {
        lock (gate)
        {
            var by = Query("SELECT status, COUNT(*) FROM batch GROUP BY status", r => (r.GetString(0), r.GetInt32(1))).ToDictionary(x => x.Item1, x => x.Item2, StringComparer.Ordinal);
            var pending = PendingBytesNoLock();
            var old = Scalar("SELECT MIN(created_at) FROM batch WHERE status IN ('QUEUED','SENDING','RETRY_WAIT')");
            var dropped = Convert.ToInt32(Scalar("SELECT COUNT(*) FROM batch WHERE dead_reason = 'OVERFLOW_DROPPED'") ?? 0, System.Globalization.CultureInfo.InvariantCulture);
            return new QueueStats(
                by,
                pending,
                old is null ? null : Convert.ToDouble(old, System.Globalization.CultureInfo.InvariantCulture),
                dropped,
                by.GetValueOrDefault("DEAD"),
                FreeBytes());
        }
    }

    /// <summary>古い ACKED / DEAD の行を消す（本文はすでに消してある）。取得セッションの行は参照されなくなったものだけ。</summary>
    public int Housekeeping(double ackedKeepSeconds = 86400.0, double deadKeepSeconds = 7 * 86400.0)
    {
        var now = Now();
        return Tx(() =>
        {
            var n = Exec("DELETE FROM batch WHERE status = 'ACKED' AND acked_at < $t", ("$t", now - ackedKeepSeconds));
            n += Exec("DELETE FROM batch WHERE status = 'DEAD' AND updated_at < $t", ("$t", now - deadKeepSeconds));
            Exec(
                "DELETE FROM fetch_session WHERE state IN ('COMPLETED','ABORTED','INTERRUPTED') AND completed_at < $t "
                + "AND NOT EXISTS (SELECT 1 FROM batch b WHERE b.fetch_id = fetch_session.fetch_id)",
                ("$t", now - deadKeepSeconds));
            return n;
        });
    }

    // ------------------------------------------------------------------ 状態・アラート
    public void SetState(string key, string value)
    {
        var now = Now();
        Tx(() => Exec(
            "INSERT INTO state (k, v, updated_at) VALUES ($k,$v,$now) ON CONFLICT(k) DO UPDATE SET v = excluded.v, updated_at = excluded.updated_at",
            ("$k", key), ("$v", value), ("$now", now)));
    }

    public string? GetState(string key)
    {
        lock (gate) return Scalar("SELECT v FROM state WHERE k = $k", ("$k", key))?.ToString();
    }

    public void RaiseAlert(string name, string detail)
    {
        var now = Now();
        Tx(() => Exec(
            "INSERT INTO alert (name, detail, raised_at) VALUES ($n,$d,$now) ON CONFLICT(name) DO UPDATE SET detail = excluded.detail",
            ("$n", name), ("$d", Cut(detail, 200)), ("$now", now)));
    }

    public bool ClearAlert(string name) => Tx(() => Exec("DELETE FROM alert WHERE name = $n", ("$n", name)) > 0);

    public IReadOnlyDictionary<string, string> Alerts()
    {
        lock (gate) return Query("SELECT name, detail FROM alert ORDER BY name", r => (r.GetString(0), r.GetString(1))).ToDictionary(x => x.Item1, x => x.Item2, StringComparer.Ordinal);
    }

    // ------------------------------------------------------------------ 検査（verify-queue）
    /// <summary>問題の一覧（空なら健全）。内容（本文）は出さない。</summary>
    public IReadOnlyList<string> Verify()
    {
        var problems = new SortedSet<string>(StringComparer.Ordinal);
        lock (gate)
        {
            var ic = Query("PRAGMA integrity_check", r => r.GetString(0));
            if (ic.Count != 1 || ic[0] != "ok") problems.Add("SQLITE_INTEGRITY");
            if (Query("PRAGMA foreign_key_check", r => 1).Count > 0) problems.Add("FOREIGN_KEY");
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM batch WHERE status = 'SENDING'"), System.Globalization.CultureInfo.InvariantCulture) > 1) problems.Add("MULTIPLE_SENDING");
            foreach (var (fid, nextSeq) in Query("SELECT fetch_id, next_seq FROM fetch_session", r => (r.GetString(0), r.GetInt64(1))))
            {
                var seqs = Query("SELECT fetch_seq FROM batch WHERE fetch_id = $f ORDER BY fetch_seq", r => r.GetInt64(0), ("$f", fid));
                if (!seqs.SequenceEqual(Enumerable.Range(0, seqs.Count).Select(i => (long)i)) || seqs.Count != nextSeq) problems.Add("FETCH_SEQ_GAP");
                if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM batch WHERE fetch_id = $f AND eof = 1", ("$f", fid)), System.Globalization.CultureInfo.InvariantCulture) > 1) problems.Add("MULTIPLE_EOF");
            }
            foreach (var c in Query("SELECT * FROM checkpoint", MapCheckpoint))
            {
                // acked が fetched より先に進むのは、自分の取得がサーバのカーソルを確定させた場合に限る（fetched ≥ acked）
                if (c.AckedTs is not null && (c.FetchedTs is null || string.CompareOrdinal(c.AckedTs, c.FetchedTs) > 0)) problems.Add("ACKED_AHEAD_OF_FETCHED");
            }
            foreach (var fid in Query("SELECT fetch_id FROM fetch_session WHERE state = 'COMPLETED' AND cursor_confirmed = 1", r => r.GetString(0)))
            {
                if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM batch WHERE fetch_id = $f AND eof = 1 AND status = 'ACKED'", ("$f", fid)), System.Globalization.CultureInfo.InvariantCulture) != 1)
                    problems.Add("COMPLETED_WITHOUT_EOF_ACK");
            }
        }
        return [.. problems];
    }
}
