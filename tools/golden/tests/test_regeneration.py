"""旧実装（固定コミット）を使う再生成テスト。GOLDEN_LEGACY_PATH 未設定ならスキップ（CI では必ず設定する）。"""
import os
import unittest

from tools.golden import constants as C, fixtures_io, generate, legacy

LEGACY = os.environ.get("GOLDEN_LEGACY_PATH")


@unittest.skipUnless(LEGACY, "GOLDEN_LEGACY_PATH 未設定（旧実装なし）")
class RegenerationTest(unittest.TestCase):
    def test_regenerated_files_equal_committed_bytes(self):
        files = generate.generate_files(LEGACY)
        committed = {p.name: p.read_bytes() for p in C.FIXTURE_DIR.glob("*.json")}
        self.assertEqual(set(files), set(committed))
        for name in files:
            self.assertEqual(files[name], committed[name], name)

    def test_two_runs_identical(self):
        self.assertEqual(generate.generate_files(LEGACY), generate.generate_files(LEGACY))

    def test_legacy_checkout_is_pinned_and_untouched(self):
        self.assertEqual(legacy.verify_checkout(LEGACY), C.PINNED_COMMIT)

    def test_every_scenario_has_fixture_with_matching_input(self):
        from tools.golden.scenarios import CASES
        files = generate.generate_files(LEGACY)
        for c in CASES:
            self.assertIn(fixtures_io.fixture_filename(c.case_id), files)
