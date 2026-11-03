using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using JraAgent.Core;

namespace JraAgent.Core.Tests;

/// <summary>ビーコンの Ed25519 検証（署名不正・kid・seq 巻き戻り・鮮度）と、状態機械（CONTRACTS §7）。旧 test_beacon_modes.py の移植。</summary>
public class BeaconAndModesTests
{
    private static readonly DateTimeOffset Now = new(2026, 11, 1, 0, 30, 0, TimeSpan.Zero);

    private static Beacon Verify(JsonObject doc, params BeaconKey[] keys) => BeaconVerifier.Verify(BeaconSigner.Bytes(doc), keys, Now);

    private static string Reason(Action a) => Assert.Throws<VerificationException>(a).Reason;

    [Fact]
    public void Beacons_signed_by_the_deleted_python_implementation_verify_byte_for_byte()
    {
        // infra の ops/test/vectors/beacon_vectors.json（削除前の Python の ops/jrabot_ops/beacon.py で生成。8 件。seq は 2^53 超を含む）
        using var v = Vectors.Load("beacon_vectors.json");
        var n = 0;
        foreach (var c in v.RootElement.GetProperty("cases").EnumerateArray())
        {
            var key = new BeaconKey(c.GetProperty("kid").GetString()!, Convert.FromBase64String(c.GetProperty("public_key_b64").GetString()!));
            var beacon = Encoding.UTF8.GetBytes(c.GetProperty("beacon_utf8").GetString()!);
            using var doc = JsonDocument.Parse(beacon);
            Assert.Equal(c.GetProperty("canonical_utf8").GetString(), Encoding.UTF8.GetString(CanonicalJson.EncodeWithout(doc.RootElement, "sig")));
            var updated = DateTimeOffset.Parse(c.GetProperty("updated_at").GetString()!, System.Globalization.CultureInfo.InvariantCulture);
            var b = BeaconVerifier.Verify(beacon, [key], updated);
            Assert.Equal(c.GetProperty("mode").GetString(), b.Mode);
            Assert.Equal(c.GetProperty("seq").GetString(), b.Seq.ToString(System.Globalization.CultureInfo.InvariantCulture));
            n++;
        }
        Assert.Equal(8, n);
    }

    [Fact]
    public void Valid_beacon_is_accepted_and_canonical_is_sorted_compact()
    {
        var s = new BeaconSigner();
        var b = Verify(s.Doc(), s.Public);
        Assert.Equal(("NORMAL", 1L, s.Kid), (b.Mode, b.Seq, b.Kid));
        using var doc = JsonDocument.Parse(BeaconSigner.Bytes(s.Doc()));
        Assert.Equal(
            """{"kid":"beacon-test-a","mode":"NORMAL","schema_version":1,"seq":1,"updatedAt":"2026-11-01T00:00:00Z","validUntil":null}""",
            Encoding.UTF8.GetString(CanonicalJson.EncodeWithout(doc.RootElement, "sig")));
    }

    public static TheoryData<string, string> Tampering() => new()
    {
        { "mode=STOPPED", "BAD_SIGNATURE" },
        { "seq=999", "BAD_SIGNATURE" },
        { "sig=A86", "BAD_SIGNATURE" },
        { "sig=!!!", "BAD_SIGNATURE" },
        { "kid=unknown", "UNKNOWN_KID" },
        { "extra=1", "BAD_SHAPE" },
        { "drop sig", "BAD_SHAPE" },
        { "schema_version=2", "BAD_SCHEMA_VERSION" },
    };

    [Theory]
    [MemberData(nameof(Tampering))]
    public void Tampered_or_malformed_beacon_is_rejected(string mutation, string reason)
    {
        var s = new BeaconSigner();
        var d = s.Doc();
        switch (mutation)
        {
            case "mode=STOPPED": d["mode"] = "STOPPED"; break;
            case "seq=999": d["seq"] = 999; break;
            case "sig=A86": d["sig"] = new string('A', 86); break;
            case "sig=!!!": d["sig"] = "!!!"; break;
            case "kid=unknown": d["kid"] = "unknown"; break;
            case "extra=1": d["extra"] = 1; break;
            case "drop sig": d.Remove("sig"); break;
            case "schema_version=2": d["schema_version"] = 2; break;
        }
        Assert.Equal(reason, Reason(() => Verify(d, s.Public)));
    }

    [Fact]
    public void Duplicate_keys_and_non_integer_numbers_are_rejected()
    {
        var s = new BeaconSigner();
        var text = BeaconSigner.Bytes(s.Doc());
        var dup = Encoding.UTF8.GetBytes(Encoding.UTF8.GetString(text).Replace("\"mode\":\"NORMAL\"", "\"mode\":\"NORMAL\",\"mode\":\"STOPPED\"", StringComparison.Ordinal));
        Assert.Equal("BAD_SHAPE", Reason(() => BeaconVerifier.Verify(dup, [s.Public], Now)));
        Assert.Equal("BAD_JSON", Reason(() => BeaconVerifier.Verify("not json"u8.ToArray(), [s.Public], Now)));
    }

    [Fact]
    public void Beacon_signed_by_another_key_with_the_same_kid_is_rejected()
    {
        var attacker = new BeaconSigner("beacon-test-a");
        var legit = new BeaconSigner("beacon-test-a");
        Assert.Equal("BAD_SIGNATURE", Reason(() => Verify(attacker.Doc(), legit.Public)));
    }

    [Fact]
    public void Kid_selects_the_public_key_during_rotation()
    {
        var a = new BeaconSigner("k-a");
        var b = new BeaconSigner("k-b");
        Assert.Equal("k-a", Verify(a.Doc(), a.Public, b.Public).Kid);
        Assert.Equal("k-b", Verify(b.Doc(), a.Public, b.Public).Kid);
        Assert.Equal("BAD_SIGNATURE", Reason(() => Verify(b.Doc(kid: "k-a"), a.Public, b.Public))); // kid を偽っても、その鍵では検証に通らない
    }

    [Fact]
    public void Expired_normal_and_future_beacons()
    {
        var s = new BeaconSigner();
        Assert.Equal("EXPIRED", Reason(() => Verify(s.Doc(validUntil: "2026-11-01T00:10:00Z"), s.Public)));
        Assert.Equal("STOPPED", Verify(s.Doc(mode: "STOPPED", validUntil: "2026-11-01T00:10:00Z"), s.Public).Mode); // 止める方向は期限切れでも受ける
        Assert.Equal("UPDATED_IN_FUTURE", Reason(() => Verify(s.Doc(updatedAt: "2026-11-01T01:00:00Z"), s.Public)));
        Assert.Equal("BAD_UPDATED_AT", Reason(() => Verify(s.Doc(updatedAt: "2026-11-01T00:00:00"), s.Public))); // タイムゾーンが無い
    }

    [Fact]
    public void Seq_must_not_roll_back_even_after_restart()
    {
        var s = new BeaconSigner();
        var st = new MemoryState();
        var g = new BeaconGuard(st.Get, st.Set);
        g.CheckAndAccept(Verify(s.Doc(seq: 10), s.Public));
        g.CheckAndAccept(Verify(s.Doc(seq: 10), s.Public)); // 同じ seq・同じ mode は再取得として受ける
        g.CheckAndAccept(Verify(s.Doc(seq: 11, mode: "STOPPED"), s.Public));
        var g2 = new BeaconGuard(st.Get, st.Set); // 再起動後（永続した値）
        Assert.Equal("SEQ_ROLLBACK", Reason(() => g2.CheckAndAccept(Verify(s.Doc(seq: 10), s.Public))));
        Assert.Equal("SEQ_CONFLICT", Reason(() => g2.CheckAndAccept(Verify(s.Doc(seq: 11, mode: "NORMAL"), s.Public))));
        g2.Reset();
        g2.CheckAndAccept(Verify(s.Doc(seq: 1), s.Public)); // 利用者の明示操作だけが巻き戻せる
    }

    [Fact]
    public void Beacon_client_statuses()
    {
        var s = new BeaconSigner();
        var clock = new FakeClock(Now);
        var t = new ScriptedTransport();
        var st = new MemoryState();
        var c = new BeaconClient("https://beacon.example/mode.json", [s.Public], t, clock, new BeaconGuard(st.Get, st.Set));
        t.Push(
            ScriptedTransport.Resp(200, BeaconSigner.Bytes(s.Doc(seq: 3))),
            new TransportException("TIMEOUT"),
            ScriptedTransport.Resp(503),
            ScriptedTransport.Resp(200, "not json"u8.ToArray()),
            ScriptedTransport.Resp(200, BeaconSigner.Bytes(s.Doc(seq: 2))),
            ScriptedTransport.Resp(200, new byte[5000]));
        Assert.Equal(BeaconStatus.Valid, c.Fetch().Status);
        Assert.Equal(BeaconStatus.Unreachable, c.Fetch().Status);
        Assert.Equal(BeaconStatus.Unreachable, c.Fetch().Status);
        var r = c.Fetch();
        Assert.Equal((BeaconStatus.Invalid, "BAD_JSON"), (r.Status, r.Reason));
        r = c.Fetch();
        Assert.Equal((BeaconStatus.Invalid, "SEQ_ROLLBACK"), (r.Status, r.Reason));
        r = c.Fetch();
        Assert.Equal((BeaconStatus.Invalid, "TOO_LARGE"), (r.Status, r.Reason));
    }

    // ------------------------------------------------------------------ 状態機械
    private static BeaconResult Valid(FakeClock clock, string mode = "NORMAL", long seq = 1) =>
        new(BeaconStatus.Valid, new Beacon(mode, seq, "beacon-test-a", clock.Now, null));

    private static readonly Classified OkResp = new(Outcome.Ok, 200);

    [Fact]
    public void Starts_unknown_and_needs_both_beacon_and_server()
    {
        var clock = new FakeClock();
        var m = new ModeMachine(clock);
        Assert.Equal(AgentMode.Unknown, m.Evaluate().Mode);
        Assert.False(m.MayFetch || m.MaySend);
        m.OnBeacon(Valid(clock));
        Assert.Equal(AgentMode.Unknown, m.Evaluate().Mode); // ビーコン NORMAL だけでは足りない
        m.OnServer(OkResp);
        Assert.Equal(AgentMode.NormalOk, m.Evaluate().Mode);
        Assert.True(m.MayFetch && m.MaySend);
    }

    [Fact]
    public void Server_200_alone_is_not_enough()
    {
        var m = new ModeMachine(new FakeClock());
        m.OnServer(OkResp);
        Assert.Equal(AgentMode.Unknown, m.Evaluate().Mode);
    }

    [Fact]
    public void Stopped_beacon_stops_everything_and_requires_a_fresh_server_ok_to_resume()
    {
        var clock = new FakeClock();
        var m = new ModeMachine(clock);
        m.OnBeacon(Valid(clock));
        m.OnServer(OkResp);
        Assert.True(m.MayFetch);
        m.OnBeacon(Valid(clock, "STOPPING", 2));
        Assert.Equal(AgentMode.StoppedConfirmed, m.Evaluate().Mode);
        Assert.False(m.MayFetch || m.MaySend);
        m.OnBeacon(Valid(clock, "STOPPED", 3));
        clock.Advance(3600);
        Assert.Equal(AgentMode.StoppedConfirmed, m.Evaluate().Mode);
        m.OnBeacon(Valid(clock, "NORMAL", 4));
        Assert.Equal(AgentMode.Unknown, m.Evaluate().Mode); // 止まる前の 200 を引きずらない
        m.OnServer(OkResp);
        Assert.Equal(AgentMode.NormalOk, m.Evaluate().Mode);
    }

    [Fact]
    public void A_503_with_ops_mode_overrides_a_stale_normal_beacon()
    {
        var clock = new FakeClock();
        var m = new ModeMachine(clock);
        m.OnBeacon(Valid(clock));
        m.OnServer(OkResp);
        m.OnServer(new Classified(Outcome.OpsNotNormal, 503, null, null, "STOPPING"));
        Assert.Equal(AgentMode.StoppedConfirmed, m.Evaluate().Mode);
        clock.Advance(60);
        m.OnServer(OkResp);
        Assert.Equal(AgentMode.NormalOk, m.Evaluate().Mode);
        var m2 = new ModeMachine(clock);
        m2.OnBeacon(Valid(clock));
        m2.OnServer(new Classified(Outcome.OpsNotNormal, 503, null, null, "STOPPED"));
        m2.OnBeacon(Valid(clock, "NORMAL", 2)); // 新しい NORMAL ビーコン
        Assert.Equal(AgentMode.Unknown, m2.Evaluate().Mode);
        m2.OnServer(OkResp);
        Assert.Equal(AgentMode.NormalOk, m2.Evaluate().Mode);
    }

    [Fact]
    public void An_invalid_beacon_is_never_ignored()
    {
        var clock = new FakeClock();
        var m = new ModeMachine(clock);
        m.OnBeacon(Valid(clock));
        m.OnServer(OkResp);
        m.OnBeacon(new BeaconResult(BeaconStatus.Invalid, Reason: "BAD_SIGNATURE"));
        var v = m.Evaluate();
        Assert.Equal((AgentMode.Unknown, "BEACON_INVALID:BAD_SIGNATURE"), (v.Mode, v.Reason));
        Assert.False(m.MayFetch || m.MaySend);
        m.OnServer(OkResp);
        Assert.Equal(AgentMode.Unknown, m.Evaluate().Mode);
        m.OnBeacon(Valid(clock, "NORMAL", 2));
        m.OnServer(OkResp);
        Assert.Equal(AgentMode.NormalOk, m.Evaluate().Mode);
    }

    [Fact]
    public void Beacon_unreachable_and_ingest_failures_become_unknown()
    {
        var clock = new FakeClock();
        var m = new ModeMachine(clock);
        m.OnBeacon(Valid(clock));
        m.OnServer(OkResp);
        m.OnBeacon(new BeaconResult(BeaconStatus.Unreachable, Reason: "TIMEOUT"));
        for (var i = 0; i < 2; i++) m.OnServer(new Classified(Outcome.Unreachable, null, "TIMEOUT"));
        Assert.Equal(AgentMode.NormalOk, m.Evaluate().Mode);
        Assert.True(m.Evaluate().SendPaused);
        m.OnServer(new Classified(Outcome.OpsUnknown, 503));
        Assert.Equal((AgentMode.Unknown, "BEACON_UNREACHABLE_AND_INGEST_FAILING"), (m.Evaluate().Mode, m.Evaluate().Reason));
    }

    [Fact]
    public void Normal_beacon_with_ingest_trouble_pauses_sending_then_unknown_after_30_minutes()
    {
        var clock = new FakeClock();
        var m = new ModeMachine(clock);
        m.OnBeacon(Valid(clock));
        m.OnServer(OkResp);
        m.OnServer(new Classified(Outcome.TransientDb, 503, "UNAVAILABLE", 5.0));
        var v = m.Evaluate();
        Assert.True(v.Mode == AgentMode.NormalOk && v.SendPaused && m.MayFetch && !m.MaySend);
        clock.Advance(31 * 60);
        m.OnBeacon(Valid(clock));
        Assert.Equal((AgentMode.Unknown, "INGEST_FAILING_30M"), (m.Evaluate().Mode, m.Evaluate().Reason));
    }

    [Fact]
    public void Stale_normal_and_stale_stopped_beacons_become_unknown()
    {
        var clock = new FakeClock();
        var m = new ModeMachine(clock, beaconMaxAgeSeconds: 3600);
        m.OnBeacon(Valid(clock));
        m.OnServer(OkResp);
        clock.Advance(3601);
        Assert.Equal((AgentMode.Unknown, "BEACON_STALE"), (m.Evaluate().Mode, m.Evaluate().Reason));
        var m3 = new ModeMachine(clock);
        m3.OnBeacon(Valid(clock, "STOPPED", 9));
        clock.Advance(6 * 3600 + 1);
        Assert.Equal((AgentMode.Unknown, "STOPPED_BEACON_STALE"), (m3.Evaluate().Mode, m3.Evaluate().Reason));
    }

    [Fact]
    public void Conflict_and_auth_responses_do_not_change_mode()
    {
        var clock = new FakeClock();
        var m = new ModeMachine(clock);
        m.OnBeacon(Valid(clock));
        m.OnServer(OkResp);
        foreach (var o in new[] { Outcome.RetryConflict, Outcome.Throttled, Outcome.Auth, Outcome.Dead }) m.OnServer(new Classified(o, 400));
        Assert.Equal(AgentMode.NormalOk, m.Evaluate().Mode);
        Assert.False(m.Evaluate().SendPaused);
    }
}
