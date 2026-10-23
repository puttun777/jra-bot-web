import shutil
import tempfile
import unittest
from pathlib import Path

from tools.golden import constants as C, generate, legacy


class AddNewTest(unittest.TestCase):
    """`generate --add-new` は新しいケースのファイルだけを足し、既存のフィクスチャを 1 バイトも変えない。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name)
        for p in C.FIXTURE_DIR.glob("*.json"):
            shutil.copy(p, self.out / p.name)
        # 旧実装を使わず、『再生成した』結果を、コミット済みの内容から作る（最後の桁が揺れたことを模擬するため 1 ケースは別バイトにする）
        self.generated = {p.name: p.read_bytes() for p in C.FIXTURE_DIR.glob("*.json")}
        self.generated["GD01-EV-001.json"] = self.generated["GD01-EV-001.json"].replace(b"\n", b"\n ", 1)

    def tearDown(self):
        self.tmp.cleanup()

    def test_adds_only_missing_cases_and_keeps_existing_bytes(self):
        new = "GD08-SELECT-003.json"
        before = {p.name: p.read_bytes() for p in self.out.glob("*.json") if p.name not in (new, C.MANIFEST_NAME)}
        (self.out / new).unlink()
        added = generate.add_new_files(self.out, self.generated)
        self.assertEqual(added, [new])
        for n, b in before.items():
            self.assertEqual((self.out / n).read_bytes(), b, n)  # 揺れた GD01-EV-001 も既存のまま
        self.assertEqual((self.out / C.MANIFEST_NAME).read_bytes(), (C.FIXTURE_DIR / C.MANIFEST_NAME).read_bytes())

    def test_nothing_to_add_leaves_everything_unchanged(self):
        before = {p.name: p.read_bytes() for p in self.out.glob("*.json")}
        self.assertEqual(generate.add_new_files(self.out, self.generated), [])
        self.assertEqual({p.name: p.read_bytes() for p in self.out.glob("*.json")}, before)

    def test_refuses_when_committed_case_is_not_in_scenarios(self):
        del self.generated["GD01-EV-001.json"]
        with self.assertRaises(legacy.LegacyError):
            generate.add_new_files(self.out, self.generated)


if __name__ == "__main__":
    unittest.main()
