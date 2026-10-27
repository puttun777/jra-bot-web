"""Discord 通知の合成入力（T3.7: G8）。旧 `notify/discord.py` のうち、ネットワークを使わない部分の入力だけ。実データ・実 Webhook・実在の馬名は使わない。

- 対象: `_split_content`（分割）、`_parse_retry_after_seconds`（429 の待機秒数）、`_deduplicate_tickets`（買い目の重複除外）、
  `send_signal` / `send_signals_grouped` の**本文**（HTTP 送信の関数を差し替えて本文と許可メンションだけを取り出す）、`format_reason_from_features`（理由文）。
- 旧実装の自動リトライ（5 回・5xx も再送）は比較対象外: 新実装は二重通知を避けるため 5xx・タイムアウトを再送しない（CONTRACTS §9.2。新仕様の単体テストで検証）。
"""
import copy

from . import scenarios_ml as M

USER_ID = "123456789012345678"  # 合成のユーザー ID（実在の ID ではない）


def _calls(prefix, function, arg_dicts):
    return [{"call_id": f"{prefix}-{i:03d}", "function": function, "args": a} for i, a in enumerate(arg_dicts)]


# ---- _split_content ---------------------------------------------------------------------------------------------

def _rep(*parts):
    """長い文字列は『(文字列, 回数)』の並びで持つ（フィクスチャに長いトークン様の文字列を置かない。実データ・秘密の混入検査を緩めないため）。
    adapter と Kotlin ランナーが同じ規則で展開する。"""
    return {"repeat": [[t, c] for t, c in parts]}


def split_calls():
    cases = []
    for n in (0, 1, 1899, 1900, 1901, 2000, 2001, 3799, 3800, 3801, 5700, 5701):
        cases.append({"content": _rep(("a", n))})
        cases.append({"content": _rep(("あ", n))})
    line = ("x", 99), ("\n", 1)
    cases += [{"content": _rep(*(line * k))} for k in (1, 19, 20, 50)]
    cases += [{"content": _rep(("a", 4000), ("\n", 1), ("b", 1))}, {"content": _rep(("a", 1899), ("\n", 1), ("b", 10))},
              {"content": _rep(("a", 1900), ("\n", 1))}, {"content": _rep(("a", 1901), ("\n", 1))}]
    cases += [{"content": _rep(("🎯", n))} for n in (1, 1899, 1900, 1901, 3801)]            # 絵文字（コードポイント数で数える旧実装の len）
    cases += [{"content": _rep(("a", 1899), ("🎯", 1), ("b", 10))}, {"content": _rep(("🐴", 950), ("\n", 1), ("🐴", 950), ("\n", 1), ("z", 1))}]
    cases += [{"content": _rep(("a\r\nb\rc\u000bd\u000ce\u001cf\u001dg\u001eh\u0085i\u2028j\u2029k", 200))}]  # Python の splitlines の区切り
    cases += [{"content": _rep(("\n", 2500))}, {"content": _rep(("\r\n", 1000))}, {"content": _rep(("競馬の期待値通知です。\n", 400))}, {"content": _rep(("競馬の期待値通知です。", 400))}]
    for limit in (2, 3, 10, 100):
        cases.append({"content": _rep(("ab\ncd🐴ef\n", 30), ("g", 20)), "kwargs": {"limit": limit}})
    return _calls("sp", "split_content", cases)


# ---- _parse_retry_after_seconds -----------------------------------------------------------------------------------

def retry_calls():
    j = lambda s: s  # noqa: E731
    cases = [
        {"body": j('{"retry_after": 2.5, "global": false}'), "headers": {"Retry-After": "9"}},
        {"body": j('{"retry_after": 3}')},
        {"body": j('{"retry_after": "1.25"}')},
        {"body": j('{"retry_after": 0}'), "headers": {"Retry-After": "4"}},
        {"body": j('{"retry_after": -1}'), "headers": {"retry-after": "5.5"}},
        {"body": j('{"retry_after": null}'), "headers": {"X-RateLimit-Reset-After": "6"}},
        {"body": j('{"message": "x"}'), "headers": {"Retry-After": "4", "X-RateLimit-Reset-After": "8"}},
        {"body": "not json", "headers": {"x-ratelimit-reset-after": "0.75"}},
        {"body": None, "headers": {"Retry-After": " 7 "}},
        {"body": None, "headers": {"Retry-After": "abc", "X-RateLimit-Reset-After": "2"}},
        {"body": None, "headers": {"Retry-After": "0", "X-RateLimit-Reset-After": "-2"}},
        {"body": None, "headers": {"Retry-After": ""}},
        {"body": None, "headers": {}},
        {"body": None},
        {"body": j("[]"), "headers": {"Retry-After": "3"}},
        {"body": j('{"retry_after": [1]}'), "headers": {"Retry-After": "3"}},
        {"body": j('{"retry_after": {"a": 1}}')},
        {"body": j('{"retry_after": true}'), "headers": {"Retry-After": "5"}},
        {"body": j('{"retry_after": false}'), "headers": {"Retry-After": "5"}},
        {"body": j('{"retry_after": "nan"}')},
        {"body": j('{"retry_after": 1e-3}')},
        {"body": j('{"retry_after": 1e2}')},
        {"body": j('{"retry_after": 120.0, "global": true}')},
        {"body": None, "headers": {"Retry-After": "1e1"}},
        {"body": None, "headers": {"Retry-After": "+3"}},
    ]
    return _calls("ra", "parse_retry_after", cases)


# ---- _deduplicate_tickets -----------------------------------------------------------------------------------------

def _t(kumi, ev, **kw):
    d = {"kumi": kumi, "odds_note": "3.2〜5.1", "odds": 3.2, "prob": 0.0345, "prob_used": 0.0345, "ev": ev}
    d.update(kw)
    return d


def dedupe_calls():
    cases = [
        {"tickets": []},
        {"tickets": [_t("01-02", 0.10), _t("01-03", 0.20)]},
        {"tickets": [_t("01-02", 0.10), _t("[BOX]01-02", 0.15), _t("01-02", 0.12)]},          # BOX の有無を無視・EV の高い方
        {"tickets": [_t("01-02", 0.15), _t("[BOX]01-02", 0.15)]},                              # 同値は先のもの
        {"tickets": [_t("[BOX]01-02-03", 0.1), _t(" 01-02-03 ", 0.2), _t("01-02-03", 0.2)]},  # 前後の空白
        {"tickets": [{"kumi": None, "ev": 0.1}, {"kumi": "", "ev": 0.2}, {"ev": 0.3}]},       # kumi が無い・空
        {"tickets": [{"kumi": "01-02"}, {"kumi": "01-02", "ev": 0.1}]},                       # ev が無い（0.0 扱い）
        {"tickets": [_t("02-01", 0.3), _t("01-02", 0.2)]},                                     # 順番違いは別の買い目のまま（旧実装）
    ]
    return _calls("dd", "dedupe_tickets", cases)


# ---- 本文: send_signal / send_signals_grouped ------------------------------------------------------------------------

RACE = {"race_id": "202601040611", "track": "合成", "race_no": 11, "name": "合成記念", "post_time": "15:25"}


def strat(type_, bet, profit, odds, prob, ev, *, tickets=None, note=None, kelly=None, prob_used=None):
    d = {"type": type_, "suggested_bet": bet, "expected_profit": profit, "odds": odds, "prob": prob, "ev": ev}
    if note:
        d["note"] = note
    if tickets is not None:
        d["tickets"] = tickets
    k = kelly if kelly is not None else {"raw": 0.1, "adjusted": 0.05, "before_clip": bet, "clipped": False, "skip": None}
    d.update({"kelly_raw": k["raw"], "kelly_adjusted": k["adjusted"], "kelly_bet_before_clip": k["before_clip"], "clipped": k["clipped"], "skip_reason": k["skip"],
              "prob_used_for_bet": prob if prob_used is None else prob_used})
    return d


def bundle(*strategies, best=None):
    sd = {s["type"]: s for s in strategies}
    b = best if best is not None else strategies[0]
    return {"strategies": sd, "best_strategy": {"type": b["type"], "ev": b["ev"], "suggested_bet": b["suggested_bet"]},
            "total_suggested_bet": sum(s["suggested_bet"] for s in strategies), "total_expected_profit": sum(s["expected_profit"] for s in strategies)}


def signal(umaban, bet_type, odds, prob, ev, *, bet=2.0, bs=None, **kw):
    p_mkt = 1.0 / odds if odds else 0.0
    d = {"umaban": umaban, "bet_type": bet_type, "odds": odds, "prob": prob, "ev": ev, "p_mkt": p_mkt, "ratio": (prob / p_mkt) if p_mkt else 0.0, "expected_return": odds * prob,
         "prob_source": "ml:alpha", "bet_amount": bet, "reason": "馬名=合成一号 / 近走3走: 平均着順=2.3", "coverage_score": 0.85, "has_horse_master": True,
         "has_past_runs": True, "past_n": 5, "beta_eff": 0.456}
    if bs is not None:
        d["bet_strategy"] = bs
    d.update(kw)
    return d


def _tickets_wide():
    return [_t("01-02", 0.10, odds_note="3.2〜5.1"), _t("[BOX]01-02", 0.15, odds_note="3.2〜5.1", stake=1.0), _t("01-03", 0.30, odds_note="12.3（推定）", stake=2.5),
            _t("02-03", 0.20, prob_used=0.0212, stake=0.0), _t("03-04", 0.05), _t("04-05", 0.06), _t("05-06", 0.07), _t("06-07", 0.08)]


def _tickets_tri():
    return [_t("01-02-03", 0.5, odds_note="55.5", prob_used=0.0101), _t("[BOX]01-02-04", 0.4, odds_note="48.0（推定）", stake=1.5)]


def single_calls():
    win_bs = bundle(strat("単勝", 2.0, 0.7, 4.5, 0.3, 0.35))
    multi = bundle(strat("単勝", 2.0, 0.7, 4.5, 0.3, 0.35), strat("ワイド", 3.0, 1.1, 5.1, 0.0345, 0.12, tickets=_tickets_wide(), note="※ 実オッズ優先（低い方でEV計算）"),
                   strat("三連複", 1.5, 0.9, 55.5, 0.0101, 0.5, tickets=_tickets_tri(), note="※ オッズは推定値"), best=None)
    cases = [
        {"race": RACE, "signal": signal(3, "単勝", 4.5, 0.3, 0.35, bs=win_bs)},
        {"race": RACE, "signal": signal(3, "単勝", 4.5, 0.3, 0.35, bs=win_bs), "mention_user_id": USER_ID},
        {"race": RACE, "signal": signal(3, "ワイド", 5.1, 0.0345, 0.12, bs=multi)},
        {"race": RACE, "signal": signal(3, "三連複", 55.5, 0.0101, 0.5, bs=multi, bet=1.5)},
        {"race": RACE, "signal": signal(7, "単勝", 12.5, 0.12345, 0.5432, bet=0.0)},                                    # 戦略なし・推奨額 0
        {"race": RACE, "signal": signal(7, "単勝", 12.5, 0.12345, 0.5432, coverage_score=1.0, has_horse_master=False, has_past_runs=False)},   # カバレッジ非表示
        {"race": RACE, "signal": signal(7, "単勝", 12.5, 0.12345, 0.5432, has_horse_master=False, has_past_runs=True, past_n=3, beta_eff=None)},
        {"race": RACE, "signal": signal(2, "単勝", 3.0, 0.285, 0.855, reason="")},                                         # 丸めの境界（%.2 / %.1）
        {"race": RACE, "signal": signal(2, "単勝", 10.0, 0.125, 0.25, reason="理由に絵文字🐴と＠と<br>")},
        {"race": {"track": "東京🐴", "race_no": 9, "name": "ＡＢＣ記念🎯"}, "signal": signal(1, "単勝", 2.0, 0.55, 0.1)},      # Unicode のレース名
        {"race": RACE, "signal": {"umaban": 4, "odds": 8.0, "prob": 0.2, "ev": 0.6, "reason": "最小の入力", "prob_source": "market"}},   # bet_type・市場・戦略なし
        {"race": RACE, "signal": {"umaban": 4, "odds": 8.0, "prob": 0.2, "ev": 0.6, "market": "wide", "reason": "x", "prob_source": "market"}},
        {"race": RACE, "signal": {"umaban": 4, "odds": 8.0, "prob": 0.2, "ev": 0.6, "market": "sanrenpuku", "reason": "x", "prob_source": "market"}},
        {"race": RACE, "signal": signal(5, "単勝", 4.0, 0.3, 0.2, p_mkt=0.0, ratio=0.0, expected_return=0.0)},             # 0 は未指定と同じ（旧 or の癖）
    ]
    return _calls("s1", "send_signal_content", cases)


def grouped_calls():
    win_bs = lambda bet=2.0, profit=0.7: bundle(strat("単勝", bet, profit, 4.5, 0.3, 0.35))  # noqa: E731
    multi = lambda wide_bet=3.0: bundle(  # noqa: E731
        strat("単勝", 2.0, 0.7, 4.5, 0.3, 0.35),
        strat("ワイド", wide_bet, 1.1, 5.1, 0.0345, 0.12, tickets=_tickets_wide(), prob_used=0.0300),
        strat("三連複", 1.5, 0.9, 55.5, 0.0101, 0.5, tickets=_tickets_tri()),
        best=None,
    )
    best_tri = lambda: dict(multi(), best_strategy={"type": "三連複", "ev": 0.5, "suggested_bet": 1.5})  # noqa: E731
    skip = signal(7, "単勝", 12.5, 0.12345, 0.0234, bet=0.0, skip_reason="Kelly推奨額(0.30点) < min_bet(1.0点)", payout_rate=0.8, show_adjusted_mkt=True)
    cases = [
        {"race": RACE, "signals": [signal(3, "単勝", 4.5, 0.3, 0.35, bs=win_bs())]},
        {"race": RACE, "signals": [signal(3, "単勝", 4.5, 0.3, 0.35, bs=win_bs())], "mention_user_id": USER_ID},
        {"race": RACE, "signals": [signal(1, "ワイド", 5.1, 0.0345, 0.12, bs=multi()), signal(2, "三連複", 55.5, 0.0101, 0.5, bs=best_tri())]},
        {"race": RACE, "signals": [signal(1, "ワイド", 5.1, 0.0345, 0.12, bs=multi(4.5)), signal(2, "ワイド", 6.1, 0.04, 0.2, bs=multi(2.5)), signal(3, "単勝", 4.5, 0.3, 0.35, bs=win_bs(1.0, 0.2))]},
        {"race": RACE, "signals": [skip]},                                                                                   # 見送りのみ
        {"race": RACE, "signals": [skip, signal(3, "単勝", 4.5, 0.3, 0.35, bs=win_bs())]},                                   # 見送り + 購入候補
        {"race": RACE, "signals": [dict(skip, show_adjusted_mkt=False), dict(skip, umaban=8, payout_rate=None)]},
        {"race": RACE, "signals": [signal(3, "単勝", 4.5, 0.3, 0.35)]},                                                      # bet_strategy なし
        {"race": RACE, "signals": [signal(3, "単勝", 0.0, 0.3, 0.35, bs=win_bs()), signal(4, "単勝", 4.5, 0.0, -1.0, bs=win_bs())]},   # odds / prob が 0（odds 行を出さない）
        {"race": RACE, "signals": [{"umaban": 9, "ev": 0.2, "odds": 6.0, "prob": 0.25}]},                                     # bet_type 省略（旧: 三連複）
        {"race": RACE, "signals": [signal(1, "ワイド", 5.1, 0.0345, 0.12, bs=bundle(strat("ワイド", 3.0, 1.1, 5.1, 0.0345, 0.12, tickets=_tickets_wide(),
                                                                                              kelly={"raw": 0.2, "adjusted": 0.1, "before_clip": 9.5, "clipped": True, "skip": None})))]},
        {"race": RACE, "signals": [signal(1, "ワイド", 5.1, 0.0345, 0.12, bs=bundle(strat("ワイド", 0.0, 0.0, 5.1, 0.0345, 0.12, tickets=[],
                                                                                              kelly={"raw": 0.0, "adjusted": 0.0, "before_clip": 0.0, "clipped": False, "skip": "実オッズ未取得のため見送り"}, note="※ 実オッズが取れた場合のみ判定/投資します")))]},
        {"race": RACE, "signals": [signal(1, "ワイド", 5.1, 0.0345, 0.12, bs=bundle(strat("ワイド", 3.0, -1.1, 5.1, 0.0345, 0.12, tickets=[_t("01-02", 0.1)], prob_used=0.0)))]},   # 期待利益が負・縮小確率 0
        {"race": RACE, "signals": [signal(1, "単勝", 4.5, 0.3, 0.35, bs=bundle(strat("未知の賭式", 5.0, 1.0, 4.5, 0.3, 0.35, tickets=[_t("a-b", 0.2)]), best=None))]},   # 未知の賭式
        {"race": RACE, "signals": [signal(u, "ワイド", 5.1, 0.03, 0.1, bs=multi()) for u in range(1, 13)]},                      # 12 件（長い本文）
        {"race": {"track": "東京🐴", "race_no": 9, "name": "ＡＢＣ記念🎯"}, "signals": [signal(1, "単勝", 2.0, 0.55, 0.1, bs=win_bs(), reason="🎯")]},
        {"race": RACE, "signals": [signal(3, "単勝", 4.5, 0.285, 0.282, bs=win_bs())]},                                       # 丸めの境界
    ]
    return _calls("sg", "send_signals_grouped_content", cases)


# ---- format_reason_from_features ------------------------------------------------------------------------------------

def notify_db():
    """G6 の合成 DB に、出走表の馬名（race_horses.bamei）を足したもの。G6 のフィクスチャの入力は変えない（コピーに足す）。"""
    db = copy.deepcopy(M.synthetic_db())
    names = {1: "合成一号", 2: "", 3: "合成三号", 4: "合成四号", 5: "", 6: "合成一号（空白つき）", 7: "合成七号", 8: "合成八号"}
    for r in db["race_horses"]:
        r["bamei"] = names.get(r["umaban"])
    return db


def reason_calls():
    db = notify_db()
    reqs = [dict(race_id=race, umaban=u) for race in (M.RACE_TURF, M.RACE_DIRT, M.RACE_OBST, M.RACE_NONE) for u in range(1, 10)]
    reqs += [dict(race_id=M.RACE_TURF, umaban=1, with_db=False), dict(race_id="", umaban=1)]
    return [
        {"call_id": "rf-000", "function": "reason_from_features", "args": dict(db=db, now=M.NOW, requests=reqs)},
        {"call_id": "rf-001", "function": "reason_from_features", "args": dict(db=db, now="2030-04-01T00:00:00", requests=[dict(race_id=M.RACE_TURF, umaban=u) for u in (1, 2, 4, 8)])},
    ]
