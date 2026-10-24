"""合成の旧モデル pickle を書き出す（結合テスト用。実 pkl ではない）。

  python -m tests.make_legacy_pkl OUT.pkl market5|calibration40|alpha35 [--dim N]
"""

import argparse
from pathlib import Path

from tests.legacy_support import legacy_pickle


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("kind", choices=["market5", "calibration40", "alpha35"])
    ap.add_argument("--dim", type=int)
    args = ap.parse_args()
    Path(args.out).write_bytes(legacy_pickle(kind=args.kind, dim=args.dim))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
