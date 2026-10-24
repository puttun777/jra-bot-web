import json
import pathlib
import tempfile
import unittest

from tools.ml import canonical, make_test_artifacts, signing

RESOURCES = pathlib.Path(__file__).resolve().parents[3] / "domain" / "src" / "test" / "resources" / "ml"


class CanonicalTest(unittest.TestCase):
    def test_known_vectors(self):
        # Kotlin の CanonicalJsonTest と同じ入力・同じ期待値
        self.assertEqual(canonical.sha256_hex({"x": 1, "y": [1.5, "é"]}), "698505d26707912b0a7e424c7b8810aeed20c48afbcaaa31c887511126418cf8")
        self.assertEqual(
            canonical.dumps([1, 1.0, 1e-4, 1e-5, 1e16, 1e22, -0.0, 123456789012345.0, 0.1, 1e2, 1.5e-7]),
            b"[1,1.0,0.0001,1e-05,1e+16,1e+22,-0.0,123456789012345.0,0.1,100.0,1.5e-07]",
        )
        self.assertEqual(canonical.dumps({"b": 1, "a": {"z": None, "y": True}}), b'{"a":{"y":true,"z":null},"b":1}')

    def test_int_and_float_differ(self):
        self.assertNotEqual(canonical.sha256_hex({"a": 1}), canonical.sha256_hex({"a": 1.0}))

    def test_key_order_is_code_point_order(self):
        self.assertEqual(canonical.dumps({"\U0001f40e": 1, "～": 2}), '{"～":2,"\U0001f40e":1}'.encode())

    def test_nan_rejected(self):
        with self.assertRaises(ValueError):
            canonical.dumps({"a": float("nan")})

    def test_strict_loads(self):
        for bad in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":Infinity}', b"\xef\xbb\xbf{}"):
            with self.assertRaises(ValueError):
                canonical.loads_strict(bad)


class FeatureSchemaContractTest(unittest.TestCase):
    """Kotlin が書いた feature_schema.*.json を、名前の一覧を再実装せずに独立に検証する（hash の再計算）。"""

    def test_hashes_match(self):
        for name, size in (("calibration40", 40), ("alpha35", 35), ("market5", 5)):
            raw = (RESOURCES / f"feature_schema.{name}.json").read_bytes()
            schema = canonical.loads_strict(raw)
            self.assertEqual(raw, canonical.dumps(schema), "ファイルが正準 JSON でない")
            self.assertEqual(len(schema["names"]), size)
            self.assertEqual(canonical.feature_schema_hash(schema), schema["hash"])
            self.assertEqual(set(schema), {"version", "names", "pedigree_dim", "hash_algo", "missing_policy", "hash"})

    def test_order_change_changes_hash(self):
        schema = canonical.loads_strict((RESOURCES / "feature_schema.alpha35.json").read_bytes())
        swapped = dict(schema, names=[schema["names"][1], schema["names"][0]] + schema["names"][2:])
        self.assertNotEqual(canonical.feature_schema_hash(swapped), schema["hash"])

    def test_alpha_and_calibration_differ(self):
        a = canonical.loads_strict((RESOURCES / "feature_schema.alpha35.json").read_bytes())
        c = canonical.loads_strict((RESOURCES / "feature_schema.calibration40.json").read_bytes())
        self.assertNotEqual(a["hash"], c["hash"])
        self.assertEqual(c["names"][5:], a["names"])
        self.assertEqual(c["names"][:5], ["odds", "popularity", "log_odds", "inv_popularity", "odds_popularity_interaction"])


class SigningTest(unittest.TestCase):
    def test_ed25519_rfc8032_vector(self):
        # RFC 8032 §7.1 TEST 1（空メッセージ）
        seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        pub = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        sig = bytes.fromhex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        self.assertEqual(signing.public_key_raw(seed), pub)
        self.assertEqual(signing.sign(seed, b""), sig)
        self.assertTrue(signing.verify(pub, sig, b""))
        self.assertFalse(signing.verify(pub, sig, b"x"))
        self.assertFalse(signing.verify(pub, sig[:-1], b""))

    def test_test_keys_are_deterministic_and_distinct(self):
        self.assertEqual(signing.public_key_b64(signing.TEST_SEED_PRIMARY), signing.public_key_b64(signing.TEST_SEED_PRIMARY))
        self.assertNotEqual(signing.public_key_b64(signing.TEST_SEED_PRIMARY), signing.public_key_b64(signing.TEST_SEED_OTHER))


class FixtureTest(unittest.TestCase):
    def test_committed_fixtures_regenerate_identically(self):
        files = make_test_artifacts.build(RESOURCES)
        for rel, data in files.items():
            self.assertEqual((RESOURCES / rel).read_bytes(), data, rel)
        committed = {str(p.relative_to(RESOURCES)) for p in (RESOURCES / "models").glob("*")}
        self.assertEqual(committed, {r for r in files if r.startswith("models/")})

    def test_good_models_verify_and_hashes_match(self):
        expected = json.loads((RESOURCES / "expected.json").read_text())
        pub = __import__("base64").b64decode(expected["public_key_primary"])
        for name, info in expected["models"].items():
            if "reject" in info:
                continue
            raw = (RESOURCES / "models" / f"{name}.json").read_bytes()
            sig = (RESOURCES / "models" / f"{name}.sig").read_bytes()
            self.assertTrue(signing.verify(pub, sig, raw), name)
            model = canonical.loads_strict(raw)
            self.assertEqual(raw, canonical.dumps(model), name)
            self.assertEqual(model["sha256"], canonical.model_sha256(model), name)
            self.assertEqual(model["sha256"], info["sha256"])
            if model["feature_schema"]:
                self.assertEqual(canonical.feature_schema_hash(model["feature_schema"]), model["feature_schema"]["hash"])
                self.assertEqual(len(model["weights"]), len(model["feature_schema"]["names"]))

    def test_wrong_key_does_not_verify(self):
        expected = json.loads((RESOURCES / "expected.json").read_text())
        pub = __import__("base64").b64decode(expected["public_key_primary"])
        raw = (RESOURCES / "models" / "signed_by_other_key.json").read_bytes()
        self.assertFalse(signing.verify(pub, (RESOURCES / "models" / "signed_by_other_key.sig").read_bytes(), raw))
        self.assertFalse(signing.verify(pub, bytes(64), raw))

    def test_check_mode_detects_drift(self):
        with tempfile.TemporaryDirectory() as d:
            out = pathlib.Path(d)
            for p in RESOURCES.glob("feature_schema.*.json"):
                (out / p.name).write_bytes(p.read_bytes())
            self.assertEqual(make_test_artifacts.main(["--out", str(out)]), 0)
            self.assertEqual(make_test_artifacts.main(["--out", str(out), "--check"]), 0)
            (out / "models" / "cal_test_001.json").write_bytes(b"{}")
            self.assertEqual(make_test_artifacts.main(["--out", str(out), "--check"]), 1)


if __name__ == "__main__":
    unittest.main()
