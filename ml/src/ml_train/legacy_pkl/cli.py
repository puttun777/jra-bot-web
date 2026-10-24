"""convert-pkl のコマンド（本体は後続で実装）。"""

import argparse
from collections.abc import Mapping

from ..errors import UsageError


def run(args: argparse.Namespace, env: Mapping[str, str]) -> int:
    raise UsageError("NOT_IMPLEMENTED", "convert-pkl は未実装")
