import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from tools.release import cli, jsonschema_lite, manifest, repo_facts

ROOT = repo_facts.repo_root()
SHA = "0123456789abcdef0123456789abcdef01234567"
REG = "asia-northeast1-docker.pkg.dev/synthetic-project/jra-bot"


def digest(n: int) -> str:  # 合成テスト用の「実在しない」digest（仮値判定に掛からないよう文字を散らす）
    return "sha256:" + f"{n:02x}a1b2c3d4e5f60718293a4b5c6d7e8f90"[:64].ljust(64, "7")


def synthetic_production(**over) -> dict:
    """テスト専用の合成 manifest。digest は実在しない。実際のレジストリ・イメージとは無関係。"""
    images = {n: f"{REG}/{n}@{digest(i + 1)}" for i, n in enumerate(repo_facts.IMAGE_NAMES)}
    doc = manifest.build_production(
        ROOT, SHA, "2026.11.0", "2026-11-01T00:00:00Z", images, "2026.11.0", "sha256:" + "ab12" * 16, "3.12.3", "synthetic test manifest"
    )
    doc.update(over)
    return doc


def local_images():
    return {
        n: {"local_tag": f"jra-bot/{n}:{SHA}", "image_id": "sha256:" + (format(i + 1, "x") + "c3d4e5f6a7b8091a2b3c4d5e6f708192")[:32] * 2}
        for i, n in enumerate(repo_facts.BUILDABLE_IMAGES)
    }


def make_root(tmp: Path, verified: list | None = None) -> Path:
    """検査に必要なファイルだけを一時ディレクトリへコピーした擬似リポジトリ。"""
    (tmp / "docs").mkdir(parents=True)
    shutil.copy(ROOT / "docs" / "CONTRACTS.md", tmp / "docs" / "CONTRACTS.md")
    shutil.copytree(ROOT / "docs" / "schemas", tmp / "docs" / "schemas")
    (tmp / "docs" / "release").mkdir()
    (tmp / "docs" / "release" / "rollback-verified.json").write_text(json.dumps({"schema_version": 1, "verified": verified or []}))
    for app in repo_facts.APPS:
        (tmp / app).mkdir()
        shutil.copy(ROOT / app / "build.gradle.kts", tmp / app / "build.gradle.kts")
    mig = tmp / "db-migration" / "src" / "main" / "resources" / "db" / "migration"
    mig.mkdir(parents=True)
    for p in (ROOT / "db-migration" / "src" / "main" / "resources" / "db" / "migration").glob("*.sql"):
        shutil.copy(p, mig / p.name)
    return tmp


class ProductionManifestTest(unittest.TestCase):
    def errors(self, doc, root=ROOT, sha=None):
        return manifest.validate_production(doc, root, sha)

    def test_valid_manifest_passes(self):
        self.assertEqual([], self.errors(synthetic_production(), sha=SHA))

    def test_missing_required_field(self):
        for field in ("git_sha", "images", "flyway", "rollback", "feature_schema_hash", "runtime", "notes", "contract_version"):
            doc = synthetic_production()
            del doc[field]
            self.assertTrue(any("必須フィールド" in e and field in e for e in self.errors(doc)), field)

    def test_unknown_field_rejected(self):
        self.assertTrue(any("未知のフィールド" in e for e in self.errors(synthetic_production(extra="x"))))

    def test_bad_git_sha(self):
        for bad in ("abc123", SHA.upper(), SHA + "0", "g" * 40, ""):
            self.assertTrue(any("git_sha" in e for e in self.errors(synthetic_production(git_sha=bad))), bad)

    def test_git_sha_must_match_expected(self):
        self.assertTrue(any("期待値" in e for e in self.errors(synthetic_production(), sha="f" * 40)))

    def test_bad_image_references(self):
        good = synthetic_production()["images"]["bot"]
        bad_refs = {
            "tag only": f"{REG}/bot:{SHA}",
            "latest tag": f"{REG}/bot:latest",
            "short digest": f"{REG}/bot@sha256:abc",
            "uppercase digest": good.upper(),
            "no registry (local image id)": "sha256:" + "ab12" * 16,
            "docker hub": f"docker.io/library/bot@{digest(9)}",
            "other repository name": f"{REG}/dashboard@{digest(9)}",
            "sha512": f"{REG}/bot@sha512:" + "a1" * 64,
            "empty": "",
        }
        for label, ref in bad_refs.items():
            doc = synthetic_production()
            doc["images"]["bot"] = ref
            self.assertTrue(any("images.bot" in e for e in self.errors(doc)), label)

    def test_placeholder_digests_rejected(self):
        for fake in ("0" * 64, "a" * 64, "ab" * 32, "deadbeef" * 8):
            doc = synthetic_production()
            doc["images"]["bot"] = f"{REG}/bot@sha256:{fake}"
            self.assertTrue(any("仮値" in e for e in self.errors(doc)), fake)

    def test_missing_image(self):
        doc = synthetic_production()
        del doc["images"]["ml-train"]
        self.assertTrue(any("images.ml-train" in e for e in self.errors(doc)))

    def test_unsupported_schema_version(self):
        for bad in (2, 0, "1", True, None):
            self.assertTrue(any("schema_version" in e for e in self.errors(synthetic_production(schema_version=bad))), repr(bad))

    def test_unsupported_contract_version(self):
        for bad in ("1.0.0", "0.5.0", "0.3.9", "0.4", "v0.4.4"):
            self.assertTrue(any("contract_version" in e for e in self.errors(synthetic_production(contract_version=bad))), bad)

    def test_contract_version_must_equal_contracts_md(self):
        cur = repo_facts.contract_version(ROOT)
        other = "0.4." + str(int(cur.split(".")[2]) + 1)
        self.assertTrue(any("CONTRACTS.md" in e for e in self.errors(synthetic_production(contract_version=other))))

    def test_required_schema_version_must_match_baked_value(self):
        baked = repo_facts.common_required_schema_version(ROOT)
        for bad in (str(int(baked) + 1), str(int(baked) - 1)):
            self.assertTrue(any("required_schema_version" in e for e in self.errors(synthetic_production(required_schema_version=bad))), bad)

    def test_flyway_must_match_migrations(self):
        doc = synthetic_production()
        doc["flyway"]["latest_version"] = "99"
        self.assertTrue(any("flyway.latest_version" in e for e in self.errors(doc)))
        doc = synthetic_production()
        doc["flyway"]["baseline"] = "2"
        self.assertTrue(any("flyway.baseline" in e for e in self.errors(doc)))

    def test_apps_disagreeing_on_required_schema_version(self):
        with tempfile.TemporaryDirectory() as t:
            root = make_root(Path(t))
            f = root / "ingest" / "build.gradle.kts"
            f.write_text(f.read_text().replace('"3"', '"4"'))
            self.assertTrue(any("食い違" in e for e in manifest.validate_production(synthetic_production(), root)))

    def test_rollback_range(self):
        doc = synthetic_production()
        doc["rollback"]["min_app_release"] = "2026.12.0"  # release より新しい
        self.assertTrue(any("より新しい" in e for e in self.errors(doc)))
        doc["rollback"]["min_app_release"] = "latest"
        self.assertTrue(any("min_app_release" in e for e in self.errors(doc)))
        doc["rollback"]["min_app_release"] = "2026.10.3"  # 古いが検証の記録が無い
        self.assertTrue(any("検証済みの記録" in e for e in self.errors(doc)))

    def test_rollback_older_release_needs_verification_record(self):
        latest = repo_facts.flyway_versions(ROOT)[-1]
        doc = synthetic_production()
        doc["rollback"]["min_app_release"] = "2026.10.3"
        with tempfile.TemporaryDirectory() as t:
            ok = make_root(Path(t), [{"app_release": "2026.10.3", "target_flyway_version": latest, "evidence": "https://example.invalid/run/1"}])
            self.assertEqual([], manifest.validate_production(doc, ok))
        with tempfile.TemporaryDirectory() as t:  # 別のスキーマ版に対する検証は流用できない
            stale = make_root(Path(t), [{"app_release": "2026.10.3", "target_flyway_version": "1", "evidence": "x"}])
            self.assertTrue(manifest.validate_production(doc, stale))
        with tempfile.TemporaryDirectory() as t:  # 根拠の無い記録は無効
            noev = make_root(Path(t), [{"app_release": "2026.10.3", "target_flyway_version": latest, "evidence": ""}])
            self.assertTrue(manifest.validate_production(doc, noev))

    def test_bad_misc_fields(self):
        for field, bad in (
            ("release", "latest"), ("built_at", "2026-11-01"), ("agent_protocol", 2), ("model_schema_versions", []),
            ("model_schema_versions", [1, 1]), ("feature_schema_hash", "sha256:abc"), ("feature_schema_hash", None),
            ("runtime", {"jdk": "21", "python": "3.12.3"}), ("runtime", {"jdk": "25", "python": None}), ("notes", "x" * 2001),
        ):
            self.assertTrue(self.errors(synthetic_production(**{field: bad})), f"{field}={bad!r}")

    def test_not_an_object(self):
        for bad in ([], "x", 1, None):
            self.assertTrue(manifest.validate_production(bad, ROOT))

    def test_generation_is_reproducible(self):
        self.assertEqual(manifest.dumps(synthetic_production()), manifest.dumps(synthetic_production()))

    def test_production_generation_refuses_unresolved_inputs(self):
        args = (ROOT, SHA, "2026.11.0", "2026-11-01T00:00:00Z")
        images = synthetic_production()["images"]
        for bad_images in ({k: v for k, v in images.items() if k != "ml-train"}, {**images, "bot": f"{REG}/bot:{SHA}"}, {}):
            with self.assertRaises(ValueError):
                manifest.build_production(*args, bad_images, "2026.11.0", "sha256:" + "ab12" * 16, "3.12.3")
        with self.assertRaises(ValueError):  # 検証されていないロールバック範囲
            manifest.build_production(*args, images, "2026.10.0", "sha256:" + "ab12" * 16, "3.12.3")
        with self.assertRaises(ValueError):  # 未定義の feature hash
            manifest.build_production(*args, images, "2026.11.0", "", "3.12.3")


class PreviewManifestTest(unittest.TestCase):
    def preview(self, **kw):
        return manifest.build_preview(ROOT, SHA, kw.pop("release", "preview-0123456789ab"), "2026-11-01T00:00:00Z", kw.pop("images", local_images()), kw.pop("python", None))

    def test_preview_is_valid_and_not_deployable(self):
        doc = self.preview()
        self.assertEqual([], manifest.validate_preview(doc, ROOT, SHA))
        self.assertIs(doc["deployable"], False)
        self.assertIn("IMAGES_NOT_PUSHED", doc["blockers"])
        self.assertIn("ROLLBACK_RANGE_UNVERIFIED", doc["blockers"])
        self.assertIn("IMAGE_NOT_BUILT", doc["blockers"])  # ml-train
        self.assertFalse(doc["images"]["ml-train"]["built"])
        self.assertIsNone(doc["images"]["bot"]["registry_digest"])
        self.assertFalse(doc["rollback"]["verified"])

    def test_preview_is_rejected_as_production(self):
        errors = manifest.validate_production(self.preview(), ROOT)
        self.assertTrue(any("プレビュー成果物" in e for e in errors), errors)

    def test_preview_with_production_shape_tricks_is_rejected(self):
        doc = synthetic_production()
        doc.update(kind="release-manifest-preview", deployable=False, blockers=["X"])
        self.assertTrue(manifest.validate_production(doc, ROOT))
        doc = synthetic_production()
        doc["deployable"] = True
        self.assertTrue(manifest.validate_production(doc, ROOT))

    def test_production_is_rejected_as_preview(self):
        self.assertTrue(manifest.validate_preview(synthetic_production(), ROOT))

    def test_preview_cannot_carry_a_registry_digest(self):
        doc = self.preview()
        doc["images"]["bot"]["registry_digest"] = "sha256:" + "ab12" * 16
        self.assertTrue(manifest.validate_preview(doc, ROOT))
        doc = self.preview()
        doc["notes"] = f"{REG}/bot@{digest(3)}"
        self.assertTrue(any("digest 形式" in e for e in manifest.validate_preview(doc, ROOT)))

    def test_preview_cannot_hide_blockers(self):
        doc = self.preview()
        doc["blockers"] = ["IMAGES_NOT_PUSHED"]
        self.assertTrue(any("blockers" in e for e in manifest.validate_preview(doc, ROOT)))
        doc["blockers"] = []
        self.assertTrue(manifest.validate_preview(doc, ROOT))
        doc = self.preview()
        doc["deployable"] = True
        self.assertTrue(manifest.validate_preview(doc, ROOT))

    def test_preview_cannot_claim_verified_rollback_or_hash(self):
        doc = self.preview()
        doc["rollback"]["verified"] = True
        self.assertTrue(manifest.validate_preview(doc, ROOT))
        doc = self.preview()
        doc["feature_schema_hash"] = None
        doc["blockers"] = [b for b in doc["blockers"] if b != "FEATURE_SCHEMA_HASH_UNDEFINED"]
        self.assertTrue(any("blockers" in e for e in manifest.validate_preview(doc, ROOT)))

    def test_preview_local_image_id_is_not_a_registry_reference(self):
        doc = self.preview()
        doc["images"]["bot"]["local_image_id"] = f"{REG}/bot@{digest(5)}"
        self.assertTrue(manifest.validate_preview(doc, ROOT))

    def test_preview_values_match_repository(self):
        doc = self.preview()
        self.assertEqual(repo_facts.flyway_versions(ROOT)[-1], doc["flyway"]["latest_version"])
        self.assertEqual(repo_facts.common_required_schema_version(ROOT), doc["required_schema_version"])
        self.assertEqual(repo_facts.contract_version(ROOT), doc["contract_version"])

    def test_preview_is_reproducible(self):
        self.assertEqual(manifest.dumps(self.preview()), manifest.dumps(self.preview()))
        reordered = dict(reversed(list(local_images().items())))
        self.assertEqual(manifest.dumps(self.preview()), manifest.dumps(self.preview(images=reordered)))

    def test_bad_git_sha_in_preview(self):
        doc = self.preview()
        doc["git_sha"] = "unknown"
        self.assertTrue(any("git_sha" in e for e in manifest.validate_preview(doc, ROOT)))


class CliTest(unittest.TestCase):
    def run_cli(self, *args):
        return cli.main(list(args))

    def test_preview_cli_end_to_end_and_reproducible(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            (t / "local.json").write_text(json.dumps({"git_sha": SHA, "images": local_images()}))
            outs = []
            for i in (1, 2):
                out = t / f"p{i}.json"
                self.assertEqual(0, self.run_cli("preview", "--git-sha", SHA, "--built-at", "2026-11-01T00:00:00Z", "--local-images", str(t / "local.json"), "--out", str(out)))
                outs.append(out.read_bytes())
            self.assertEqual(outs[0], outs[1])
            self.assertEqual(0, self.run_cli("validate", "--kind", "preview", "--expect-git-sha", SHA, str(t / "p1.json")))
            self.assertEqual(1, self.run_cli("validate", "--kind", "production", str(t / "p1.json")))  # 本番利用は拒否

    def test_cli_rejects_bad_inputs(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            (t / "local.json").write_text(json.dumps({"git_sha": SHA, "images": local_images()}))
            self.assertEqual(2, self.run_cli("preview", "--git-sha", "xyz", "--built-at", "2026-11-01T00:00:00Z", "--local-images", str(t / "local.json"), "--out", str(t / "o.json")))
            self.assertEqual(2, self.run_cli("preview", "--git-sha", "f" * 40, "--built-at", "2026-11-01T00:00:00Z", "--local-images", str(t / "local.json"), "--out", str(t / "o.json")))
            self.assertEqual(1, self.run_cli("preview", "--git-sha", SHA, "--built-at", "yesterday", "--local-images", str(t / "local.json"), "--out", str(t / "o.json")))
            self.assertFalse((t / "o.json").exists())
            self.assertEqual(2, self.run_cli("validate", "--kind", "production", str(t / "missing.json")))
            self.assertEqual(2, self.run_cli("nonsense"))

    def test_production_cli_refuses_now(self):
        """今は push 済みの digest も ml-train も無いので、本番用は生成できない（ダミーで通さない）。"""
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            (t / "reg.json").write_text(json.dumps({"bot": f"{REG}/bot:{SHA}"}))
            rc = self.run_cli("production", "--git-sha", SHA, "--release", "2026.11.0", "--built-at", "2026-11-01T00:00:00Z", "--registry-images", str(t / "reg.json"),
                              "--min-app-release", "2026.11.0", "--feature-schema-hash", "sha256:" + "ab12" * 16, "--python-version", "3.12.3", "--out", str(t / "release-manifest.json"))
            self.assertEqual(1, rc)
            self.assertFalse((t / "release-manifest.json").exists())


class SchemaFilesTest(unittest.TestCase):
    def test_schemas_use_only_supported_keywords(self):
        for name in (manifest.PRODUCTION_SCHEMA, manifest.PREVIEW_SCHEMA):
            jsonschema_lite.check_schema_supported(manifest.load_schema(ROOT, name))

    def test_unsupported_keyword_fails_closed(self):
        with self.assertRaises(jsonschema_lite.UnsupportedSchema):
            jsonschema_lite.validate({}, {"type": "object", "oneOf": []})
        with self.assertRaises(jsonschema_lite.UnsupportedSchema):
            jsonschema_lite.validate({}, {"type": "object", "properties": {"a": {"allOf": []}}})

    def test_contract_example_shape(self):
        """CONTRACTS §10 の例のキーと、本番スキーマの required が一致する。"""
        text = (ROOT / "docs" / "CONTRACTS.md").read_text(encoding="utf-8")
        block = text.split("## 10.")[1].split("```json")[1].split("```")[0]
        example_keys = set(json.loads(block.replace("…", "x").replace("<40桁>", "x").replace("<prj>", "p").replace("<64桁>", "0")).keys())
        self.assertEqual(example_keys, set(manifest.load_schema(ROOT, manifest.PRODUCTION_SCHEMA)["required"]))

    def test_validator_basics(self):
        s = {"type": "object", "required": ["a"], "additionalProperties": False, "properties": {"a": {"type": "integer", "minimum": 1}, "n": {"const": None}}}
        self.assertEqual([], jsonschema_lite.validate({"a": 1, "n": None}, s))
        self.assertTrue(jsonschema_lite.validate({"a": True}, s))  # bool は integer ではない
        self.assertTrue(jsonschema_lite.validate({"a": 0}, s))
        self.assertTrue(jsonschema_lite.validate({"a": 1, "n": 0}, s))
        self.assertTrue(jsonschema_lite.validate({}, s))
        self.assertTrue(jsonschema_lite.validate({"a": 1, "z": 1}, s))


class RepoFactsTest(unittest.TestCase):
    def test_facts(self):
        self.assertRegex(repo_facts.contract_version(ROOT), r"^\d+\.\d+\.\d+$")
        self.assertEqual({"dashboard", "ingest", "bot"}, set(repo_facts.required_schema_versions(ROOT)))
        self.assertEqual(["1", "2", "3"], repo_facts.flyway_versions(ROOT))
        self.assertEqual((10,), repo_facts.version_key("10"))
        self.assertGreater(repo_facts.version_key("10"), repo_facts.version_key("9"))


if __name__ == "__main__":
    unittest.main()
