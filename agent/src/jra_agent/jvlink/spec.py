"""JV-Link 仕様の確認状況（T1.17 の 12 項目。docs/JVLINK_DESIGN.md §12.2）。

**公式仕様書で確認できた項目だけが `CONFIRMED`**。現在は 0 件（仕様書 PDF に到達できていない）。`require()` は未確認の項目が 1 つでもあれば拒否するので、
**本番の取得（`jvlink.mode = "com"`）は、確認が記録されるまで開始されない**。確認が取れたら、仕様書名・版・節/ページを `source` に書いて
`CONFIRMED` に変える（コード変更 + レビュー）。環境変数や設定ファイルで迂回する手段は作らない。

実機 PoC（agent/poc/）は、利用者が明示的に実行したときだけ、このゲートとは別の「PoC 許可リスト」（poc/ のマニフェスト）で、
**参照元実装で使われている呼出しだけ**を試す。PoC の結果は集計だけを持ち帰り、確認が取れたものだけを本ファイルに反映する。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class SpecStatus(Enum):
    UNCONFIRMED = "UNCONFIRMED"
    CONFIRMED = "CONFIRMED"


class SpecUnconfirmedError(Exception):
    """公式仕様で確認できていない項目に依存する処理を実行しようとした。"""

    def __init__(self, items: list[str]) -> None:
        super().__init__("公式仕様未確認の項目: " + ", ".join(items))
        self.items = items


@dataclass(frozen=True)
class SpecItem:
    id: str
    title: str
    status: SpecStatus = SpecStatus.UNCONFIRMED
    source: str | None = None  # 確認済みなら「仕様書名 版 節/ページ」


# JVLINK_DESIGN §12.2 の 12 項目 + 取得に必要な周辺（すべて未確認）。参照元実装の使用実態は二次情報で、確認には数えない
_ITEMS = [
    SpecItem("JVINIT", "JVInit の引数（ソフトウェア ID）と戻り値"),
    SpecItem("JVOPEN_OPTION", "JVOpen の option の値と意味（T1.17 #1）"),
    SpecItem("JVOPEN_RETURN", "JVOpen の戻り値（readcount・downloadcount・lastfiletimestamp・エラーコード）"),
    SpecItem("JVRTOPEN_KEY", "JVRTOpen の key 形式と dataspec（T1.17 #2）"),
    SpecItem("JVREAD_RETURN", "JVRead の戻り値（-1 ファイル切替・-3 ダウンロード中・0 EOF・-402/-403）とバッファ（T1.17 #3）"),
    SpecItem("JVGETS_BYTES", "JVGets（バイト取得）の利用可否と pywin32 での型（T1.17 #4）"),
    SpecItem("JVCLOSE", "JVClose の戻り値と、読み途中で呼んだときの挙動"),
    SpecItem("DATA_KUBUN", "データ区分 0・9 の意味と順位（T1.17 #8）"),
    SpecItem("TIMESTAMP_RULES", "取得日時・作成日時の規則（T1.17 #9）"),
    SpecItem("RT_RETENTION", "速報データの保持期間（T1.17 #10）"),
    SpecItem("RECORD_LAYOUT", "レコード長とバイト位置・文字コード（T1.17 #12）"),
    SpecItem("DATASPEC_LIST", "dataspec の公式一覧（RACE・YSCH・RCVN・RCOV・0B11〜0B35）"),
]

# 取得の種類ごとに依存する項目
FETCH_STORED_ITEMS = ("JVINIT", "JVOPEN_OPTION", "JVOPEN_RETURN", "JVREAD_RETURN", "JVCLOSE", "DATASPEC_LIST", "TIMESTAMP_RULES")
FETCH_REALTIME_ITEMS = ("JVINIT", "JVRTOPEN_KEY", "JVREAD_RETURN", "JVCLOSE", "DATASPEC_LIST")
READ_BYTES_ITEMS = ("JVGETS_BYTES",)  # JVGets を使う場合。JVRead の文字列を cp932 に再エンコードする方式は RECORD_LAYOUT の確認も要る


class SpecRegistry:
    def __init__(self, items: list[SpecItem] | None = None) -> None:
        self._items = {i.id: i for i in (items if items is not None else _ITEMS)}

    def status(self, item_id: str) -> SpecStatus:
        return self._items[item_id].status

    def unconfirmed(self, item_ids: tuple[str, ...] | list[str]) -> list[str]:
        return [i for i in item_ids if self._items[i].status is not SpecStatus.CONFIRMED]

    def require(self, *item_ids: str) -> None:
        missing = self.unconfirmed(item_ids)
        if missing:
            raise SpecUnconfirmedError(missing)

    def summary(self) -> dict[str, str]:
        return {i.id: i.status.value for i in self._items.values()}

    @classmethod
    def all_confirmed_for_tests(cls) -> SpecRegistry:
        """**テスト専用**: Fake の JV-Link で取得の流れを検証するための、全項目が確認済みの仮の表。本番の経路では使わない。"""
        return cls([SpecItem(i.id, i.title, SpecStatus.CONFIRMED, "test-only") for i in _ITEMS])


DEFAULT_REGISTRY = SpecRegistry()
