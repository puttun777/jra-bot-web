import dataclasses
import unittest

from tools.golden import jsonutil


@dataclasses.dataclass
class D:
    a: int
    b: float


class NormalizeTest(unittest.TestCase):
    def test_dataclass_and_tuple(self):
        self.assertEqual(jsonutil.normalize({"x": D(1, 2.5), "t": (1, 2)}), {"x": {"a": 1, "b": 2.5}, "t": [1, 2]})

    def test_nonfinite_is_tagged(self):
        self.assertEqual(jsonutil.normalize(float("nan")), {"$float": "nan"})
        self.assertEqual(jsonutil.normalize(float("-inf")), {"$float": "-inf"})
        self.assertEqual(jsonutil.find_nonfinite({"a": [1, {"$float": "inf"}]}), ["$.a[1]"])

    def test_non_str_key_rejected(self):
        with self.assertRaises(TypeError):
            jsonutil.normalize({(1, 2): 3})

    def test_unknown_type_rejected(self):
        with self.assertRaises(TypeError):
            jsonutil.normalize({1, 2})

    def test_dumps_is_canonical_and_rejects_nan(self):
        self.assertEqual(jsonutil.dumps({"b": 1, "a": "あ"}), '{\n  "a": "あ",\n  "b": 1\n}\n')
        with self.assertRaises(ValueError):
            jsonutil.dumps({"a": float("nan")})

    def test_loads_rejects_nan_literal(self):
        with self.assertRaises(ValueError):
            jsonutil.loads('{"a": NaN}')

    def test_float_roundtrip_is_exact(self):
        for v in (0.1, 1 / 3, 1e-300, 123456789.123456789, 5e-324):
            self.assertEqual(jsonutil.loads(jsonutil.dumps({"v": v}))["v"], v)
