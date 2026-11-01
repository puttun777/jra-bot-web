"""T3.9 決済（GD11）の合成入力。旧実装の `ResultRepository.get_winning_combo_keys` / `EvaluationRepository.settle_evaluations_for_race` /
`BetsRepository.settle_open_win_bets_for_race` / `BetsRepository.get_payout` を、合成 SQLite（旧スキーマ）に対して呼ぶ。実データは使わない。

1 回の呼び出し（call）= 1 つの合成 DB + 操作列（ops）。結果は『各操作の戻り値』と『操作後の関連行』。時刻列（settled_at）は `datetime.now()` で
非決定的なので値は出さず、設定されたかどうか（真偽）だけを比較する。
"""

POST = "15:40"
DATE = "20260301"
BEFORE = "2026-03-01T15:00:00"  # 発走前
AFTER = "2026-03-01T16:00:00"  # 発走後


def _race(rid, date=DATE, post=POST):
    return dict(race_id=rid, date=date, track="東京", race_no=11, post_time=post)


def _res(rid, *finishes):
    """finishes: (umaban, finish) の並び。finish=None は着順なし。"""
    return [dict(race_id=rid, umaban=u, finish=f, payout=None) for u, f in finishes]


def _ev(eid, rid, snap, cand, created="2026-03-01T14:00:00"):
    return dict(id=eid, race_id=rid, snapshot_at=snap, best_candidate_json=cand, created_at=created)


def _cand(bt, key):
    import json

    return json.dumps({"bet_type": bt, "combo_key": key}, ensure_ascii=False)


def _pay(rid, market, key, payout, prov=0):
    return dict(race_id=rid, market=market, key=key, payout=payout, is_provisional=prov)


def _combo_odds(rid, market, key, odds, at="2026-03-01T15:30:00"):
    return dict(race_id=rid, collected_at=at, market=market, combo_key=key, odds=odds)


def _bet(bid, rid, market, selection, stake, odds, status=None):
    return dict(id=bid, race_id=rid, ts="2026-03-01T14:50:00", market=market, stake=stake, selection=selection, odds_at_bet=odds, status=status)


def _call(call_id, db, ops):
    return dict(call_id=call_id, function="settle_batch", args=dict(db=db, ops=ops))


def winning_keys_calls():
    calls = []
    cases = {
        "normal": [(1, 3), (2, 1), (3, 2), (4, 4)],  # 1着=2, 2着=3, 3着=1
        "two_only": [(5, 1), (7, 2)],
        "one_only": [(5, 1)],
        "none": [],
        "all_null": [(1, None), (2, None), (3, None)],
        "zero_finish": [(1, 0), (2, 0), (3, 1), (4, 2), (5, 3)],  # 0 は除外
        "scratch_mixed": [(1, None), (2, 1), (3, 2), (4, 3), (5, 0)],
        "tie_first": [(3, 1), (6, 1), (9, 3)],  # 同着 1 着（旧は SQL の並び＝馬番順の挙動）
        "tie_third": [(1, 1), (2, 2), (8, 3), (4, 3)],  # 3 着同着: 3 連複は並びに依存（旧挙動）
        "big_umaban": [(18, 1), (2, 2), (10, 3)],  # ソートは数値（"10" < "2" の文字列順ではない）
        "gap_finish": [(1, 2), (2, 5), (3, 9)],  # 欠番の着順
    }
    for name, fin in cases.items():
        rid = f"W-{name}"
        db = dict(races=[_race(rid)], results=_res(rid, *fin))
        calls.append(_call(f"wk-{name}", db, [dict(op="winning_keys", race_id=rid)]))
    calls.append(_call("wk-unknown-race", dict(races=[], results=[]), [dict(op="winning_keys", race_id="NOPE")]))
    return calls


def eval_calls():
    calls = []

    def one(name, db, race_id, extra_ops=()):
        ops = [dict(op="settle_evaluations", race_id=race_id)] + list(extra_ops)
        calls.append(_call(f"ev-{name}", db, ops))

    fin = [(1, 3), (2, 1), (3, 2), (4, 4)]  # wide=2-3, sanrenpuku=1-2-3

    # 的中（ワイド・確定払戻 350 円/100 円 → 3.5 倍）
    r = "E-wide-hit"
    one("wide-hit", dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "3-2"))],
                         payouts=[_pay(r, "wide", "2-3", 350.0)]), r)
    # 的中（三連複）
    r = "E-sanren-hit"
    one("sanren-hit", dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("三連複", "3-1-2"))],
                           payouts=[_pay(r, "sanrenpuku", "1-2-3", 1280.0)]), r)
    # 外れ（payout_used=0.0）
    r = "E-wide-miss"
    one("wide-miss", dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "1-4"))],
                          payouts=[_pay(r, "wide", "2-3", 350.0)]), r)
    # 的中だが払戻が無い → combo_odds の最新スナップショットで暫定
    r = "E-prov-odds"
    one("prov-odds", dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "2-3"))],
                          combo_odds_snapshots=[_combo_odds(r, "wide", "2-3", 4.2, "2026-03-01T15:10:00"), _combo_odds(r, "wide", "2-3", 4.8, "2026-03-01T15:35:00")]), r)
    # 的中だが払戻が 0 → スナップショットへ
    r = "E-pay-zero"
    one("pay-zero", dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "2-3"))],
                         payouts=[_pay(r, "wide", "2-3", 0.0)], combo_odds_snapshots=[_combo_odds(r, "wide", "2-3", 3.3)]), r)
    # 的中だが払戻もスナップショットも無い → hit=1 payout_used=None
    r = "E-no-payout"
    one("no-payout", dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "2-3"))]), r)
    # 暫定払戻（payouts.is_provisional=1）でも旧は確定扱い（is_provisional=0・/100）
    r = "E-prov-row"
    one("prov-row", dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "2-3"))],
                         payouts=[_pay(r, "wide", "2-3", 200.0, prov=1)]), r)
    # 着順不足 → no_ticket
    r = "E-short"
    one("short", dict(races=[_race(r)], results=_res(r, (1, 1)), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "1-2"))]), r)
    r = "E-short3"
    one("short3-sanren", dict(races=[_race(r)], results=_res(r, (1, 1), (2, 2)), race_evaluations=[_ev(1, r, BEFORE, _cand("三連複", "1-2-3"))]), r)
    # 結果なし
    r = "E-noresult"
    one("noresult", dict(races=[_race(r)], race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "1-2"))]), r)
    # best_candidate なし／壊れた JSON／単勝／配列／キー無し
    for name, cand in (("nocand", None), ("badjson", "{not json"), ("win", _cand("単勝", "3")), ("array", "[1,2]"), ("nokey", '{"bet_type": "ワイド"}'),
                       ("unknown-type", _cand("馬連", "1-2")), ("english-wide", _cand("wide", "2-3")), ("english-sanren", _cand("sanrenpuku", "2-3-1"))):
        r = f"E-{name}"
        one(name, dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, cand)],
                       payouts=[_pay(r, "wide", "2-3", 150.0), _pay(r, "sanrenpuku", "1-2-3", 900.0)]), r)
    # best_candidate の別形式: kumi / combo / tickets[0]
    for name, cand in (("kumi", '{"bet_type": "ワイド", "kumi": "3-2"}'), ("combo", '{"type": "ワイド", "combo": "2-3"}'),
                       ("tickets", '{"bet_type": "ワイド", "tickets": [{"kumi": "3-2"}, {"kumi": "1-4"}]}'),
                       ("tickets-combo", '{"bet_type": "ワイド", "tickets": [{"combo_key": "2-3"}]}')):
        r = f"E-c-{name}"
        one(f"cand-{name}", dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, cand)],
                                 payouts=[_pay(r, "wide", "2-3", 150.0)]), r)
    # ticket_evaluations が best_candidate より優先（accepted 優先・rank 昇順）
    r = "E-ticket"
    one("ticket-priority", dict(
        races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "1-4"))],
        ticket_evaluations=[
            dict(id=1, race_evaluation_id=1, race_id=r, snapshot_at=BEFORE, bet_type="ワイド", combo_key="1-4", odds=9.0, rank=1, accepted=0),
            dict(id=2, race_evaluation_id=1, race_id=r, snapshot_at=BEFORE, bet_type="ワイド", combo_key="3-2", odds=5.0, rank=2, accepted=1),
            dict(id=3, race_evaluation_id=1, race_id=r, snapshot_at=BEFORE, bet_type="単勝", combo_key="3", odds=5.0, rank=1, accepted=1),
        ], payouts=[_pay(r, "wide", "2-3", 410.0)]), r)
    r = "E-ticket-nokey"
    one("ticket-empty-key", dict(
        races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "2-3"))],
        ticket_evaluations=[dict(id=1, race_evaluation_id=1, race_id=r, snapshot_at=BEFORE, bet_type="ワイド", combo_key="", odds=9.0, rank=1, accepted=1)],
        payouts=[_pay(r, "wide", "2-3", 410.0)]), r)

    # 代表評価の選び方
    r = "E-rep-latest"  # 発走前の最新 1 件だけが決済される。発走後の評価は選ばない
    one("rep-latest", dict(races=[_race(r)], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 300.0)], race_evaluations=[
        _ev(1, r, "2026-03-01T14:00:00", _cand("ワイド", "2-3")), _ev(2, r, "2026-03-01T15:30:00", _cand("ワイド", "1-4")),
        _ev(3, r, AFTER, _cand("ワイド", "2-3"))]), r)
    r = "E-rep-tie"  # snapshot_at 同値 → created_at 降順 → id 降順
    one("rep-tie", dict(races=[_race(r)], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 300.0)], race_evaluations=[
        _ev(1, r, BEFORE, _cand("ワイド", "2-3"), "2026-03-01T14:00:00"), _ev(2, r, BEFORE, _cand("ワイド", "1-4"), "2026-03-01T14:00:00"),
        _ev(3, r, BEFORE, _cand("ワイド", "2-3"), "2026-03-01T13:00:00")]), r)
    r = "E-rep-allafter"  # 発走前が 0 件 → 全評価の最新 1 件
    one("rep-allafter", dict(races=[_race(r)], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 300.0)], race_evaluations=[
        _ev(1, r, "2026-03-01T16:00:00", _cand("ワイド", "2-3")), _ev(2, r, "2026-03-01T17:00:00", _cand("ワイド", "1-4"))]), r)
    r = "E-rep-boundary"  # 発走時刻ちょうど（<= は発走前）
    one("rep-boundary", dict(races=[_race(r)], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 300.0)], race_evaluations=[
        _ev(1, r, "2026-03-01T15:40:00", _cand("ワイド", "2-3")), _ev(2, r, "2026-03-01T15:40:01", _cand("ワイド", "1-4"))]), r)
    r = "E-rep-dateonly"  # snapshot_at が日付のみ（T00:00:00 に補完）・秒より長い文字列（19 文字で切る）
    one("rep-dateonly", dict(races=[_race(r)], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 300.0)], race_evaluations=[
        _ev(1, r, "2026-03-01", _cand("ワイド", "2-3")), _ev(2, r, "2026-03-01T15:39:59.999999", _cand("ワイド", "1-4"))]), r)
    r = "E-rep-nosnap"  # snapshot_at が空 → created_at で比較
    one("rep-nosnap", dict(races=[_race(r)], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 300.0)], race_evaluations=[
        _ev(1, r, "", _cand("ワイド", "1-4"), "2026-03-01T13:00:00"), _ev(2, r, "", _cand("ワイド", "2-3"), "2026-03-01T14:00:00")]), r)
    r = "E-rep-baddate"  # 日付が 8 桁でない → 発走時刻不明 → 全評価から最新
    one("rep-baddate", dict(races=[_race(r, date="2026-03-01")], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 300.0)], race_evaluations=[
        _ev(1, r, BEFORE, _cand("ワイド", "1-4")), _ev(2, r, AFTER, _cand("ワイド", "2-3"))]), r)
    r = "E-rep-nopost"  # post_time が空 → 00:00 扱い（全評価が発走後）
    one("rep-nopost", dict(races=[_race(r, post="")], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 300.0)], race_evaluations=[
        _ev(1, r, BEFORE, _cand("ワイド", "1-4")), _ev(2, r, AFTER, _cand("ワイド", "2-3"))]), r)
    r = "E-rep-postsec"  # post_time が HH:MM:SS（5 文字でない → そのまま）
    one("rep-postsec", dict(races=[_race(r, post="15:40:30")], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 300.0)], race_evaluations=[
        _ev(1, r, "2026-03-01T15:40:20", _cand("ワイド", "2-3")), _ev(2, r, "2026-03-01T15:40:40", _cand("ワイド", "1-4"))]), r)
    r = "E-no-eval"  # 評価なし
    one("no-eval", dict(races=[_race(r)], results=_res(r, *fin)), r)

    # 再実行（冪等）と force、結果の後着（暫定→確定）
    r = "E-idem"
    db = dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "2-3"))], payouts=[_pay(r, "wide", "2-3", 350.0)])
    calls.append(_call("ev-idempotent", db, [dict(op="settle_evaluations", race_id=r), dict(op="settle_evaluations", race_id=r)]))
    r = "E-force"
    db = dict(races=[_race(r)], results=_res(r, *fin), race_evaluations=[_ev(1, r, BEFORE, _cand("ワイド", "2-3"))],
              combo_odds_snapshots=[_combo_odds(r, "wide", "2-3", 4.0)])
    calls.append(_call("ev-force-after-provisional", db, [
        dict(op="settle_evaluations", race_id=r),  # 暫定（払戻なし・オッズ 4.0）
        dict(op="settle_evaluations", race_id=r),  # 決済済みは何もしない
        dict(op="add_payout", **_pay(r, "wide", "2-3", 520.0)),
        dict(op="settle_evaluations", race_id=r),  # まだ何もしない（force でない）
        dict(op="settle_evaluations", race_id=r, force=True),  # 確定へ置換
    ]))
    r = "E-rep-switch"  # 決済後に新しい（発走前の）評価が増えても、決済済みの代表が変わると新しい行が決済される（旧は『最新 1 件』を毎回選ぶ）
    db = dict(races=[_race(r)], results=_res(r, *fin), payouts=[_pay(r, "wide", "2-3", 350.0)],
              race_evaluations=[_ev(1, r, "2026-03-01T14:00:00", _cand("ワイド", "2-3"))])
    calls.append(_call("ev-late-evaluation", db, [
        dict(op="settle_evaluations", race_id=r),
        dict(op="add_evaluation", **_ev(2, r, "2026-03-01T15:00:00", _cand("ワイド", "1-4"))),
        dict(op="settle_evaluations", race_id=r),
    ]))
    return calls


def win_bet_calls():
    calls = []
    r = "B-1"
    fin = [(1, 3), (2, 1), (3, 2)]
    db = dict(races=[_race(r), _race("B-OTHER")], results=_res(r, *fin), bets=[
        _bet(1, r, "win", "2", 100.0, 3.5),  # 的中: payout=350 profit=250
        _bet(2, r, "win", "1", 100.0, 8.0),  # 外れ: payout=0 profit=-100
        _bet(3, r, "win", "9", 100.0, 8.0),  # 結果に馬番なし（finish=NULL）→ 未確定のまま
        _bet(4, r, "win", "2", 0.0, 3.5),  # stake 0 → payout 0 profit 0
        _bet(5, r, "win", "2", 100.0, 0.0),  # odds 0 → payout 0 profit -100
        _bet(6, r, "win", "2", 250.0, 1.0),  # odds 1.0 → payout=stake
        _bet(7, r, "win", "2", 100.0, 3.5, status="settled"),  # 決済済みは対象外
        _bet(8, r, "win", "2", 100.0, 3.5, status="open"),
        _bet(9, r, "wide", "2-3", 100.0, 3.5),  # win 以外は対象外
        _bet(10, "B-OTHER", "win", "2", 100.0, 3.5),  # 別レース
        _bet(11, r, "win", "3", 33.3, 2.55),  # 浮動小数（丸めない）
    ])
    calls.append(_call("wb-basic", db, [dict(op="settle_win_bets", race_id=r), dict(op="settle_win_bets", race_id=r)]))
    r = "B-2"  # 同着 1 着（両方的中）／着順 0（取消）／結果が後から届く
    db = dict(races=[_race(r)], results=_res(r, (4, 1), (5, 1), (6, 0)), bets=[
        _bet(1, r, "win", "4", 100.0, 5.0), _bet(2, r, "win", "5", 100.0, 7.0), _bet(3, r, "win", "6", 100.0, 9.0)])
    calls.append(_call("wb-dead-heat", db, [dict(op="settle_win_bets", race_id=r)]))
    r = "B-3"
    db = dict(races=[_race(r)], results=[], bets=[_bet(1, r, "win", "2", 100.0, 3.5)])
    calls.append(_call("wb-late-result", db, [
        dict(op="settle_win_bets", race_id=r),
        dict(op="add_result", race_id=r, umaban=2, finish=1),
        dict(op="settle_win_bets", race_id=r),
    ]))
    r = "B-4"  # selection が数値でない（CAST で 0 → 結果に馬番 0 が無いので未確定）
    db = dict(races=[_race(r)], results=_res(r, (1, 1)), bets=[_bet(1, r, "win", "abc", 100.0, 3.0), _bet(2, r, "win", " 1", 100.0, 3.0)])
    calls.append(_call("wb-selection-text", db, [dict(op="settle_win_bets", race_id=r)]))
    return calls


def payout_calls():
    r = "P-1"
    db = dict(races=[_race(r)], payouts=[_pay(r, "wide", "1-2", 260.0), _pay(r, "sanrenpuku", "1-2-3", 4520.0), _pay(r, "wide", "1-3", 0.0)])
    ops = [dict(op="get_payout", race_id=r, market=m, key=k) for m, k in
           (("wide", "1-2"), ("wide", "2-1"), ("sanrenpuku", "1-2-3"), ("wide", "1-3"), ("wide", "9-9"), ("win", "1"))]
    return [_call("gp-basic", db, ops)]


def all_calls():
    return winning_keys_calls() + eval_calls() + win_bet_calls() + payout_calls()
