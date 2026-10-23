"""ビルド済みイメージ（ローカルのタグ）のセキュリティ・起動スモーク（T2.5 後半）。標準ライブラリ + docker CLI だけ。

- レジストリ・GCP・インターネットには接続しない。アプリのコンテナは原則 `--network none`。DB が要る検証だけ、使い捨ての MySQL コンテナ
  （CI 専用・合成データ・ランダムなパスワード）と同じ一時ネットワークに置く。実データ・本番の秘密は使わない。
- 実行できなかった検査は PASS にしない（FAIL）。結果は JSON で保存する（`--report-dir`）。
- 出力: `smoke-report.json`、`images-local.json`（release-manifest プレビューの入力。ローカルの image ID であり、レジストリの digest ではない）。

使い方: python -m tools.release.image_smoke --git-sha <40桁> [--prefix jra-bot] [--tag <git sha>] [--mysql-image mysql:8.4] [--report-dir DIR]
"""
from __future__ import annotations

import argparse
import io
import json
import re
import secrets
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from . import repo_facts

UID = "10001:10001"
ENTRYPOINT_JVM = ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError"]
EXPECTED_ENTRYPOINT = {
    "dashboard": ENTRYPOINT_JVM + ["-jar", "/app/app.jar"],
    "ingest": ENTRYPOINT_JVM + ["-jar", "/app/app.jar"],
    "bot": ENTRYPOINT_JVM + ["-jar", "/app/app.jar"],
    "db-migration": ENTRYPOINT_JVM + ["-cp", "/app/lib/*", "jp.jrabot.dbmigration.DbMigrationMainKt"],
}
SECRET_ENV_NAME = re.compile(r"(PASSWORD|SECRET|TOKEN|CREDENTIAL|PRIVATE|API_?KEY|ACCESS_?KEY)", re.I)
FORBIDDEN_FILE = re.compile(
    r"(^|/)(\.env(\..*)?|id_rsa|id_ed25519|credentials[^/]*\.json|service-account[^/]*\.json|[^/]*\.(pem|key|p12|jks|pfx|sqlite3?|db|pkl|pickle|dump|bak|tfstate|tfvars|pdf))$", re.I
)
PRIVATE_KEY = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")
SECRET_ASSIGNMENT = re.compile(r"(?im)^\s*[\w.\-]*(password|secret|token|api[_-]?key)[\w.\-]*\s*[=:]\s*(?!\$\{)(?!\s*$)(\S+)")
TEXT_RESOURCE = re.compile(r"\.(properties|ya?ml|xml|json|sql|txt|md|conf|toml)$")


# calendar-check（T3.3）は監視ウィンドウ・鮮度の設定が必須（既定値なし。CONTRACTS §2）。合成のテスト値で、運用の既定値ではない
CALENDAR_ENV = {
    "CALENDAR_WATCH_START_MINUTES": "120",
    "CALENDAR_PRESTART_MINUTES": "10",
    "CALENDAR_SETTLE_DEADLINE_MINUTES": "90",
    "CALENDAR_STALE_AFTER_MINUTES": "120",
}


class Report:
    def __init__(self) -> None:
        self.results: list[dict] = []

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self.results.append({"name": name, "ok": bool(ok), "detail": detail})
        print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  -- {detail}" if detail and not ok else ""), flush=True)
        return bool(ok)

    @property
    def failed(self) -> list[dict]:
        return [r for r in self.results if not r["ok"]]


def docker(*args: str, timeout: int = 180, check: bool = False, input: bytes | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], capture_output=True, timeout=timeout, check=check, input=input)


def text(b: bytes) -> str:
    return b.decode("utf-8", "replace")


class Smoke:
    def __init__(self, prefix: str, tag: str, git_sha: str, mysql_image: str, report_dir: Path | None) -> None:
        self.prefix, self.tag, self.git_sha, self.mysql_image, self.report_dir = prefix, tag, git_sha, mysql_image, report_dir
        self.r = Report()
        self.canaries: list[str] = []  # ログ・イメージに出てはならない値（テスト用のパスワード）
        self.captured: list[tuple[str, str]] = []  # (説明, stdout+stderr) 全実行の出力
        self.network = f"jrasmoke-{secrets.token_hex(4)}"
        self.mysql = f"jrasmoke-mysql-{secrets.token_hex(4)}"
        self.tmp = Path(tempfile.mkdtemp(prefix="jrasmoke-"))
        self.facts = repo_facts.repo_root()
        self.passwords = {u: f"ci-only-{secrets.token_hex(8)}" for u in ("root", "migrate", "app", "ingest", "bot", "mltrain")}
        self.canaries = list(self.passwords.values())
        self.created: list[str] = []

    def image(self, name: str) -> str:
        return f"{self.prefix}/{name}:{self.tag}"

    # ------------------------------------------------------------------ 実行ヘルパ
    def run(self, name: str, env: dict[str, str] | None = None, args: list[str] | None = None, network: str | None = None,
            mounts: list[str] | None = None, desc: str = "", timeout: int = 90, entrypoint: str | None = None) -> tuple[int, str, str]:
        """使い捨てコンテナで実行（読み取り専用の rootfs、/tmp は tmpfs、権限を落とす）。network 未指定は none。"""
        cmd = ["run", "--rm", "--read-only", "--tmpfs", "/tmp", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--network", network or "none"]
        for k, v in (env or {}).items():
            cmd += ["-e", f"{k}={v}"]
        for m in mounts or []:
            cmd += ["-v", m]
        if entrypoint:
            cmd += ["--entrypoint", entrypoint]
        cmd.append(self.image(name))
        cmd += args or []
        try:
            p = docker(*cmd, timeout=timeout)
        except subprocess.TimeoutExpired:
            self.r.check(f"timeout: {desc or name}", False, f"{timeout}s")
            return 124, "", "timeout"
        out, err = text(p.stdout), text(p.stderr)
        self.captured.append((desc or f"{name} {args}", out + "\n" + err))
        return p.returncode, out, err

    def mysql_exec(self, sql: str, user: str = "root", password: str | None = None, database: str = "") -> str:
        p = docker("exec", "-e", f"MYSQL_PWD={password or self.passwords['root']}", self.mysql, "mysql", f"-u{user}", "-N", "-B", database, "-e", sql, timeout=60)
        if p.returncode != 0:
            raise RuntimeError(f"mysql: {text(p.stderr)[:200]}")
        return text(p.stdout).strip()

    def connections(self) -> int:
        return int(self.mysql_exec("SHOW GLOBAL STATUS LIKE 'Connections'").split()[-1])

    # ------------------------------------------------------------------ 静的検査
    def static_checks(self, name: str) -> dict | None:
        img = self.image(name)
        p = docker("image", "inspect", img)
        if not self.r.check(f"[{name}] image exists", p.returncode == 0, text(p.stderr)[:200]):
            return None
        info = json.loads(p.stdout)[0]
        cfg = info["Config"]
        self.r.check(f"[{name}] ENTRYPOINT is exec form and exactly as designed", cfg["Entrypoint"] == EXPECTED_ENTRYPOINT[name], str(cfg["Entrypoint"]))
        self.r.check(f"[{name}] no CMD (args from Cloud Run are appended to ENTRYPOINT)", not cfg.get("Cmd"), str(cfg.get("Cmd")))
        self.r.check(f"[{name}] runs as numeric non-root user {UID}", cfg["User"] == UID, cfg["User"])
        labels = cfg.get("Labels") or {}
        self.r.check(f"[{name}] OCI label revision == git sha", labels.get("org.opencontainers.image.revision") == self.git_sha, str(labels.get("org.opencontainers.image.revision")))
        self.r.check(f"[{name}] OCI label version == git sha (not the base image's)", labels.get("org.opencontainers.image.version") == self.git_sha, str(labels.get("org.opencontainers.image.version")))
        self.r.check(f"[{name}] OCI label source present", labels.get("org.opencontainers.image.source", "").startswith("https://github.com/"))
        env_names = [e.split("=", 1)[0] for e in cfg.get("Env", [])]
        self.r.check(f"[{name}] image env has no secret-like names", not [n for n in env_names if SECRET_ENV_NAME.search(n)], str(env_names))
        env = dict(e.split("=", 1) for e in cfg.get("Env", []))
        self.r.check(f"[{name}] TZ=Asia/Tokyo baked (CONTRACTS §2)", env.get("TZ") == "Asia/Tokyo")
        hist = text(docker("history", "--no-trunc", "--format", "{{.CreatedBy}}", img).stdout)
        self.r.check(f"[{name}] build history has no secret assignments / ARG secrets", not re.search(r"(?i)(password|secret|token|api_?key)\s*=", hist))
        self.r.check(f"[{name}] no HEALTHCHECK (Cloud Run uses service probes)", not cfg.get("Healthcheck"))

        rc, out, err = self.run(name, args=["-version"], entrypoint="java", desc=f"{name} java -version")
        self.r.check(f"[{name}] runs on JDK 25", rc == 0 and re.search(r'version "25\.', out + err) is not None, (out + err)[:120])
        rc, out, _ = self.run(name, args=["-u"], entrypoint="id", desc=f"{name} id")
        self.r.check(f"[{name}] effective uid is 10001", out.strip() == "10001", out.strip())
        rc, out, _ = self.run(name, args=["-c", "for c in javac jlink gradle mvn git curl wget gcc make unzip; do command -v $c; done"], entrypoint="sh", desc=f"{name} tools")
        self.r.check(f"[{name}] no build tools / downloaders in the runtime image", out.strip() == "", out.strip())
        rc, _, _ = self.run(name, args=["-c", "touch /app/x"], entrypoint="sh", desc=f"{name} write /app")
        self.r.check(f"[{name}] /app is not writable by the app user", rc != 0)
        return {"image_id": info["Id"], "size_bytes": info["Size"], "local_tag": img}

    def filesystem_checks(self, name: str) -> None:
        """コンテナのファイルシステム全体の名前を走査する（秘密・実データっぽいファイルが無いこと）。"""
        c = text(docker("create", self.image(name), check=True).stdout).strip()
        self.created.append(c)
        proc = subprocess.Popen(["docker", "export", c], stdout=subprocess.PIPE)
        names: list[str] = []
        modes: dict[str, int] = {}
        assert proc.stdout is not None
        with tarfile.open(fileobj=proc.stdout, mode="r|") as tar:
            for m in tar:
                names.append(m.name)
                if m.name.startswith("app"):
                    modes[m.name] = m.mode
                if m.name.startswith("app/") and m.isdir():
                    names.pop()  # ディレクトリは「ファイルの一覧」に含めない
        proc.wait()
        user_areas = [n for n in names if re.match(r"^(app|home|root|tmp|srv|opt/(?!java))", n) or re.match(r"^etc/[^/]*\.env", n)]
        bad = [n for n in user_areas if FORBIDDEN_FILE.search(n)]
        self.r.check(f"[{name}] no secret/real-data file names in app areas (/app /home /root /tmp /srv /opt)", not bad, str(bad[:5]))
        app_files = sorted(n for n in names if n.startswith("app/"))
        writable = [n for n, mode in modes.items() if mode & 0o022]
        self.r.check(f"[{name}] nothing under /app is writable, directories are traversable", not writable and all(m & 0o111 for n, m in modes.items() if n in ("app/lib",)), str(writable[:5]))
        if name == "db-migration":
            ok = all(re.fullmatch(r"app/lib/[\w.\-]+\.jar", n) for n in app_files) and any("flyway-core" in n for n in app_files) and any("mysql-connector-j" in n for n in app_files)
        else:
            ok = app_files == ["app/app.jar"]
        self.r.check(f"[{name}] /app contains only the expected artifacts", ok, str(app_files[:8]))
        docker("rm", c)

    def jar_checks(self, name: str) -> None:
        c = text(docker("create", self.image(name), check=True).stdout).strip()
        self.created.append(c)
        required = repo_facts.required_schema_versions(self.facts)
        try:
            if name == "db-migration":
                p = docker("cp", f"{c}:/app/lib/.", str(self.tmp / "dbm-lib"))
                jars = list((self.tmp / "dbm-lib").glob("db-migration-*.jar"))
                self.r.check("[db-migration] app jar found", p.returncode == 0 and len(jars) == 1)
                names = zipfile.ZipFile(jars[0]).namelist() if jars else []
                sql = sorted(Path(n).name for n in names if n.startswith("db/migration/") and n.endswith(".sql"))
                expected = sorted(p.name for p in (self.facts / "db-migration/src/main/resources/db/migration").glob("*.sql"))
                self.r.check("[db-migration] image embeds exactly the repository's migrations", sql == expected, str(sql))
                return
            p = docker("cp", f"{c}:/app/app.jar", str(self.tmp / f"{name}.jar"))
            self.r.check(f"[{name}] app.jar extractable", p.returncode == 0)
            z = zipfile.ZipFile(self.tmp / f"{name}.jar")
            names = z.namelist()
            props = dict(
                line.split("=", 1) for line in z.read("BOOT-INF/classes/jrabot-app.properties").decode().splitlines() if "=" in line and not line.startswith("#")
            )
            self.r.check(f"[{name}] baked requiredSchemaVersion == build.gradle.kts ({required[name]})", props.get("requiredSchemaVersion") == required[name], str(props))
            self.r.check(f"[{name}] baked releaseId == git sha", props.get("releaseId") == self.git_sha, props.get("releaseId", ""))
            self.r.check(f"[{name}] no Flyway in the app image (CONTRACTS §4.1)", not [n for n in names if "flyway" in n.lower()])
            self.r.check(f"[{name}] MySQL driver present", any("mysql-connector-j" in n for n in names))
            self.r.check(f"[{name}] no secret/real-data file names inside the jar", not [n for n in names if FORBIDDEN_FILE.search(n)], str([n for n in names if FORBIDDEN_FILE.search(n)][:5]))
            leaks = []
            for n in names:
                if n.startswith("BOOT-INF/classes/") and TEXT_RESOURCE.search(n):
                    body = z.read(n).decode("utf-8", "replace")
                    if PRIVATE_KEY.search(body) or SECRET_ASSIGNMENT.search(body):
                        leaks.append(n)
            self.r.check(f"[{name}] no secret-like assignments in own resources", not leaks, str(leaks))
        finally:
            docker("rm", c)

    # ------------------------------------------------------------------ 動作検査（DB なし）
    def bot_checks_no_db(self) -> None:
        pw = self.passwords["bot"]
        rc, out, _ = self.run("bot", desc="bot no args")
        self.r.check("[bot] no args -> exit 2", rc == 2, f"rc={rc}")
        rc, out, _ = self.run("bot", args=["--task=nonsense", "--tenant-id=1"], desc="bot invalid task")
        self.r.check("[bot] invalid --task -> exit 2", rc == 2, f"rc={rc}")
        rc, out, _ = self.run("bot", args=["--task=calendar-check", "--tenant-id=1"], desc="bot no env")
        self.r.check("[bot] valid task but no required settings -> exit 2 (fail safe)", rc == 2, f"rc={rc}")
        rc, out, _ = self.run("bot", args=["--task=calendar-check"], env={"APP_ENV": "local"}, desc="bot no tenant")
        self.r.check("[bot] missing --tenant-id -> exit 2", rc == 2, f"rc={rc}")
        bad_db = {"DB_URL": "jdbc:mysql://127.0.0.1:1/jrabot", "DB_USER": "bot", "DB_PASSWORD": pw}
        rc, out, _ = self.run("bot", args=["--task=calendar-check", "--tenant-id=1"], env={"APP_ENV": "local", "OPS_MODE_SOURCE": "/nonexistent/ops-state.json", **CALENDAR_ENV, **bad_db}, desc="bot UNKNOWN")
        self.r.check("[bot] ModeProvider UNKNOWN (unreadable source) -> exit 75", rc == 75, f"rc={rc}")
        rc, out, _ = self.run("bot", args=["--task=calendar-check", "--tenant-id=1"], env={"APP_ENV": "prod", "OPS_MODE_SOURCE": "/nonexistent/ops-state.json", **CALENDAR_ENV, **bad_db}, desc="bot prod local file")
        self.r.check("[bot] prod refuses a local-file mode source -> exit 2 (never 0)", rc == 2, f"rc={rc}")
        rc, out, _ = self.run("bot", args=["--task=bot-run", "--tenant-id=1"], env={"APP_ENV": "prod", "OPS_MODE_SOURCE": "gs://synthetic-bucket/state/ops-state.json", **bad_db}, desc="bot prod gcs unreachable")
        self.r.check("[bot] prod, mode source unreachable (no network) -> exit 75, never 0", rc == 75, f"rc={rc}")
        rc, out, _ = self.run("bot", args=["--task=bot-run", "--tenant-id=1"], env={"APP_ENV": "prod", "OPS_MODE_SOURCE": "gs://synthetic-bucket/state/ops-state.json", **{**bad_db, "DB_USER": "migrate"}}, desc="bot prod migrate user")
        self.r.check("[bot] prod with a non-bot DB user -> exit 2", rc == 2, f"rc={rc}")

    def dbm_checks_no_db(self) -> None:
        rc, out, _ = self.run("db-migration", desc="dbm no args")
        self.r.check("[db-migration] no args -> exit 2 and empty stdout", rc == 2 and out.strip() == "", f"rc={rc}")
        rc, out, _ = self.run("db-migration", args=["--mode=bogus"], desc="dbm bogus mode")
        self.r.check("[db-migration] invalid --mode -> exit 2", rc == 2, f"rc={rc}")
        rc, out, _ = self.run("db-migration", args=["--mode=info"], desc="dbm no env")
        self.r.check("[db-migration] --mode=info without DB_URL/DB_USER/DB_PASSWORD -> exit 2", rc == 2 and out.strip() == "", f"rc={rc}")
        env = {"DB_URL": "jdbc:mysql://127.0.0.1:1/jrabot", "DB_USER": "migrate", "DB_PASSWORD": self.passwords["migrate"]}
        rc, out, _ = self.run("db-migration", args=["--mode=info"], env=env, desc="dbm unreachable")
        self.r.check("[db-migration] CLI starts; DB unreachable -> exit 75 with one JSON line on stdout", rc == 75 and len(out.strip().splitlines()) == 1 and "unavailable" in out, f"rc={rc} out={out[:80]}")

    def web_checks_no_db(self, name: str, extra: dict[str, str]) -> None:
        env = {"APP_ENV": "prod"}
        rc, _, err = self.run(name, env=env, desc=f"{name} prod no settings", timeout=120)
        self.r.check(f"[{name}] APP_ENV=prod without required settings fails to start (nonzero, no hang)", rc not in (0, 124), f"rc={rc}")
        rc, _, _ = self.run(name, desc=f"{name} no env", timeout=120)
        self.r.check(f"[{name}] no environment at all fails to start (nonzero)", rc not in (0, 124), f"rc={rc}")
        env = {"APP_ENV": "prod", "OPS_MODE_SOURCE": "/nonexistent/ops-state.json", "DB_URL": "jdbc:mysql://127.0.0.1:1/jrabot", "DB_USER": "x", "DB_PASSWORD": self.passwords["app"], **extra}
        rc, _, _ = self.run(name, env=env, desc=f"{name} prod with local mode file", timeout=120)
        self.r.check(f"[{name}] prod with a local-file mode source fails to start (nonzero)", rc not in (0, 124), f"rc={rc}")

    # ------------------------------------------------------------------ 動作検査（CI 専用 MySQL）
    def start_mysql(self) -> bool:
        docker("network", "create", self.network, check=True)
        p = docker("run", "-d", "--name", self.mysql, "--network", self.network, "-e", f"MYSQL_ROOT_PASSWORD={self.passwords['root']}", "-e", "MYSQL_DATABASE=jrabot", self.mysql_image, timeout=300)
        if not self.r.check("CI MySQL container starts", p.returncode == 0, text(p.stderr)[:200]):
            return False
        for _ in range(90):
            try:
                if self.mysql_exec("SELECT 1") == "1":
                    # 初期化中の一時サーバと区別するため、データベースが作られていることまで確認する
                    if "jrabot" in self.mysql_exec("SHOW DATABASES"):
                        break
            except Exception:  # noqa: BLE001 - 起動待ち
                pass
            time.sleep(2)
        else:
            return self.r.check("CI MySQL becomes ready", False, "timeout")
        statements = [f"CREATE USER '{u}'@'%' IDENTIFIED BY '{self.passwords[u]}'" for u in ("migrate", "app", "ingest", "bot", "mltrain")]
        statements.append("GRANT ALL PRIVILEGES ON jrabot.* TO 'migrate'@'%' WITH GRANT OPTION")
        self.mysql_exec("; ".join(statements))
        return self.r.check("CI MySQL ready with synthetic users", True)

    def jdbc(self) -> str:
        return f"jdbc:mysql://{self.mysql}:3306/jrabot"

    def dbm_checks_with_db(self) -> None:
        env = {"DB_URL": self.jdbc(), "DB_USER": "migrate", "DB_PASSWORD": self.passwords["migrate"]}
        versions = repo_facts.flyway_versions(self.facts)
        rc, out, _ = self.run("db-migration", args=["--mode=info"], env=env, network=self.network, desc="dbm info empty")
        j = _json_line(out)
        self.r.check("[db-migration] info on an empty DB -> exit 0, current=null, latest=repo latest", rc == 0 and j.get("current") is None and j.get("latest") == versions[-1], f"rc={rc} {out[:120]}")
        rc, out, _ = self.run("db-migration", args=["--mode=validate"], env=env, network=self.network, desc="dbm validate pending")
        self.r.check("[db-migration] validate with pending migrations -> exit 10", rc == 10, f"rc={rc} {out[:120]}")
        rc, out, _ = self.run("db-migration", args=["--mode=migrate"], env=env, network=self.network, desc="dbm migrate", timeout=180)
        j = _json_line(out)
        self.r.check("[db-migration] migrate against MySQL -> exit 0, success, current=latest", rc == 0 and j.get("success") is True and j.get("current") == versions[-1] and not j.get("pending"), f"rc={rc} {out[:160]}")
        rc, out, _ = self.run("db-migration", args=["--mode=validate"], env=env, network=self.network, desc="dbm validate")
        self.r.check("[db-migration] validate after migrate -> exit 0", rc == 0 and _json_line(out).get("valid") is True, f"rc={rc} {out[:120]}")
        rc, out, _ = self.run("db-migration", args=["--mode=info"], env=env, network=self.network, desc="dbm info")
        j = _json_line(out)
        self.r.check("[db-migration] info after migrate: current == latest == repo latest", rc == 0 and j.get("current") == j.get("latest") == versions[-1], out[:120])
        rc, out, _ = self.run("db-migration", args=["--mode=info"], env={**env, "DB_PASSWORD": "wrong-" + secrets.token_hex(4)}, network=self.network, desc="dbm wrong password")
        self.r.check("[db-migration] wrong password -> exit 1 (not 75), stdout is one JSON line without the password", rc == 1 and len(out.strip().splitlines()) == 1, f"rc={rc} {out[:100]}")

    def bot_checks_with_db(self) -> None:
        d = self.tmp / "ops"
        d.mkdir(exist_ok=True)
        d.chmod(0o755)
        for mode in ("NORMAL", "STOPPED"):
            f = d / f"{mode}.json"
            f.write_text(
                json.dumps({
                    "schema_version": 1, "seq": 1, "mode": mode,
                    "desired": {"mode": "NORMAL" if mode == "NORMAL" else "STOPPED", "set_by": "github:synthetic", "set_at": "2026-11-01T10:00:00Z", "reason": "smoke"},
                    "operation": {"id": "op-1", "type": "stop", "status": "SUCCEEDED", "force": False},
                })
            )
            f.chmod(0o644)
        base = {"APP_ENV": "local", "DB_URL": self.jdbc(), "DB_USER": "bot", "DB_PASSWORD": self.passwords["bot"], **CALENDAR_ENV}
        mounts = [f"{d}:/ops:ro"]
        tasks = ["bot-run", "calendar-check", "reconcile", "retention", "export-dataset"]
        before = self.connections()
        codes = {t: self.run("bot", args=[f"--task={t}", "--tenant-id=1"], env={**base, "OPS_MODE_SOURCE": "/ops/STOPPED.json"}, network=self.network, mounts=mounts, desc=f"bot STOPPED {t}")[0] for t in tasks}
        after = self.connections()
        self.r.check("[bot] STOPPED -> exit 0 for all 5 tasks", set(codes.values()) == {0}, str(codes))
        self.r.check("[bot] STOPPED never opens a DB connection (server Connections delta == 1: the probe itself)", after - before == 1, f"delta={after - before}")
        before = self.connections()
        rc, out, err = self.run("bot", args=["--task=reconcile", "--tenant-id=1"], env={**base, "OPS_MODE_SOURCE": "/ops/NORMAL.json"}, network=self.network, mounts=mounts, desc="bot NORMAL local stub", timeout=120)
        after = self.connections()
        self.r.check("[bot] NORMAL (local only) connects to the DB and runs the stub task -> exit 0", rc == 0 and after - before >= 2, f"rc={rc} delta={after - before}\n{(out + err)[-300:]}")
        self.r.check("[bot] the stub says so in the log (TASK_NOT_IMPLEMENTED, result STUB_LOCAL_OK: local only)", "TASK_NOT_IMPLEMENTED" in out + err and "STUB_LOCAL_OK" in out + err, (out + err)[-300:])
        # calendar-check は実装済み（T3.3）。予定の無い DB・取得の事実なしでは「不明」= exit 75 で、起動を要求しない（スタブの成功ではない）
        rc, out, err = self.run("bot", args=["--task=calendar-check", "--tenant-id=1"], env={**base, "OPS_MODE_SOURCE": "/ops/NORMAL.json"}, network=self.network, mounts=mounts, desc="bot calendar-check unknown", timeout=120)
        self.r.check("[bot] calendar-check on an empty schedule -> exit 75 SCHEDULE_UNKNOWN (fail closed, no launch)", rc == 75 and "SCHEDULE_UNKNOWN" in out + err, f"rc={rc}\n{(out + err)[-300:]}")
        rc, _, _ = self.run("bot", args=["--task=calendar-check", "--tenant-id=1"], env={**base, "OPS_MODE_SOURCE": "/ops/NORMAL.json", "DB_PASSWORD": "wrong-" + secrets.token_hex(4)}, network=self.network, mounts=mounts, desc="bot wrong pw", timeout=120)
        self.r.check("[bot] DB authentication failure -> exit 1 (not 75, not 0)", rc == 1, f"rc={rc}")
        # スキーマ版不足: 履歴の表の無い別 DB
        self.mysql_exec("CREATE DATABASE IF NOT EXISTS emptydb; GRANT SELECT ON emptydb.* TO 'bot'@'%'")
        rc, _, _ = self.run("bot", args=["--task=calendar-check", "--tenant-id=1"], env={**base, "DB_URL": f"jdbc:mysql://{self.mysql}:3306/emptydb", "OPS_MODE_SOURCE": "/ops/NORMAL.json"}, network=self.network, mounts=mounts, desc="bot schema missing", timeout=120)
        self.r.check("[bot] schema version missing -> exit 1 (does not run the task, does not migrate)", rc == 1, f"rc={rc}")
        self.r.check("[bot] the app did not create tables in the empty DB", self.mysql_exec("SHOW TABLES", database="emptydb") == "")

    def web_checks_with_db(self, name: str, user: str, extra: dict[str, str]) -> None:
        d = self.tmp / "ops"
        env = {"APP_ENV": "local", "OPS_MODE_SOURCE": "/ops/NORMAL.json", "DB_URL": self.jdbc(), "DB_USER": user, "DB_PASSWORD": self.passwords[user], "PORT": "8080", **extra}
        cname = f"jrasmoke-{name}-{secrets.token_hex(3)}"
        cmd = ["run", "-d", "--name", cname, "--read-only", "--tmpfs", "/tmp", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--network", self.network,
               "-p", "127.0.0.1::8080", "-v", f"{d}:/ops:ro"]
        for k, v in env.items():
            cmd += ["-e", f"{k}={v}"]
        cmd.append(self.image(name))
        p = docker(*cmd)
        self.created.append(cname)
        if not self.r.check(f"[{name}] container starts", p.returncode == 0, text(p.stderr)[:200]):
            return
        port = text(docker("port", cname, "8080/tcp").stdout).strip().splitlines()[0].rsplit(":", 1)[1]
        status: dict[str, int] = {}
        deadline = time.time() + 120
        while time.time() < deadline:
            try:
                for path in ("/internal/health", "/internal/ready"):
                    status[path] = _http_status(f"http://127.0.0.1:{port}{path}")
                break
            except (urllib.error.URLError, ConnectionError, socket.timeout, OSError):
                if docker("inspect", "-f", "{{.State.Running}}", cname).stdout.strip() != b"true":
                    break
                time.sleep(2)
        self.r.check(f"[{name}] Tomcat is up on $PORT and /internal/* requires a signed token (401 without one)", status == {"/internal/health": 401, "/internal/ready": 401}, str(status))
        # ログは標準出力・標準エラー（docker logs）に出ていて、秘密を含まない
        logs = text(docker("logs", cname).stdout) + text(docker("logs", cname).stderr)
        self.captured.append((f"{name} server logs", logs))
        self.r.check(f"[{name}] logs go to stdout/stderr", len(logs.strip()) > 0)
        t0 = time.time()
        p = docker("stop", "-t", "10", cname, timeout=60)
        took = time.time() - t0
        st = json.loads(docker("inspect", cname).stdout)[0]["State"]
        self.r.check(f"[{name}] SIGTERM stops the JVM gracefully within the grace period (exit 0/143, not 137/OOM)", st["ExitCode"] in (0, 143) and not st["OOMKilled"] and took < 9.5, f"exit={st['ExitCode']} took={took:.1f}s")

    # ------------------------------------------------------------------ 全体
    def secret_scan_outputs(self) -> None:
        leaked = [(d, c) for d, o in self.captured for c in self.canaries if c in o]
        self.r.check("test passwords never appear in any container stdout/stderr/log", not leaked, str([d for d, _ in leaked][:5]))

    def cleanup(self) -> None:
        for c in self.created:
            docker("rm", "-f", c)
        docker("rm", "-f", self.mysql)
        docker("network", "rm", self.network)

    def run_all(self) -> int:
        infos: dict[str, dict] = {}
        try:
            for name in repo_facts.BUILDABLE_IMAGES:
                info = self.static_checks(name)
                if info:
                    infos[name] = info
                    self.filesystem_checks(name)
                    self.jar_checks(name)
            self.r.check("all 4 buildable images exist (ml-train is intentionally not built: Stage 3)", set(infos) == set(repo_facts.BUILDABLE_IMAGES))
            if set(infos) == set(repo_facts.BUILDABLE_IMAGES):
                self.bot_checks_no_db()
                self.dbm_checks_no_db()
                self.web_checks_no_db("ingest", {"INTERNAL_ID_TOKEN_AUDIENCE": "https://ingest.example.invalid", "OPS_SA_EMAIL": "ops@example.invalid"})
                self.web_checks_no_db("dashboard", {"IAP_AUDIENCE": "/projects/0/global/backendServices/0", "OPS_SA_EMAIL": "ops@example.invalid"})
                if self.start_mysql():
                    self.dbm_checks_with_db()
                    self.bot_checks_with_db()
                    self.web_checks_with_db("ingest", "ingest", {"INTERNAL_ID_TOKEN_AUDIENCE": "https://ingest.example.invalid", "OPS_SA_EMAIL": "ops@example.invalid"})
                    self.web_checks_with_db("dashboard", "app", {"IAP_AUDIENCE": "/projects/0/global/backendServices/0", "OPS_SA_EMAIL": "ops@example.invalid"})
            self.secret_scan_outputs()
        except Exception as e:  # noqa: BLE001 - 実行できなかった検査を成功にしない
            self.r.check(f"smoke run aborted: {type(e).__name__}", False, str(e)[:300])
        finally:
            self.cleanup()
        if self.report_dir:
            self.report_dir.mkdir(parents=True, exist_ok=True)
            (self.report_dir / "smoke-report.json").write_text(json.dumps({"git_sha": self.git_sha, "passed": len(self.r.results) - len(self.r.failed), "failed": len(self.r.failed), "results": self.r.results}, indent=2, ensure_ascii=False) + "\n")
            (self.report_dir / "images-local.json").write_text(json.dumps({"git_sha": self.git_sha, "images": {n: {"local_tag": i["local_tag"], "image_id": i["image_id"]} for n, i in infos.items()}}, indent=2) + "\n")
        print(f"\nsmoke: {len(self.r.results) - len(self.r.failed)} passed, {len(self.r.failed)} failed")
        for f in self.r.failed:
            print(f"  FAILED: {f['name']} -- {f['detail']}")
        return 1 if self.r.failed else 0


def _json_line(out: str) -> dict:
    try:
        return json.loads(out.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {}


def _http_status(url: str) -> int:
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--git-sha", required=True)
    p.add_argument("--prefix", default="jra-bot")
    p.add_argument("--tag")
    p.add_argument("--mysql-image", default="mysql:8.4")
    p.add_argument("--report-dir", type=Path)
    a = p.parse_args(argv)
    if not re.fullmatch(r"[0-9a-f]{40}", a.git_sha):
        print("git_sha は 40 桁の小文字 16 進", file=sys.stderr)
        return 2
    return Smoke(a.prefix, a.tag or a.git_sha, a.git_sha, a.mysql_image, a.report_dir).run_all()


if __name__ == "__main__":
    sys.exit(main())
