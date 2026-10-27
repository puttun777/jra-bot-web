INSERT INTO race_evaluations (
  tenant_id, race_id, snapshot_at, model_type_used, model_id, fallback_reason, bet_types, priority_mode,
  ev_threshold, win_odds_max, win_prob_min, win_ratio_min, best_ev, rejection_summary_json, signal_sent, created_at
)
SELECT
  /* entity.tenantId */0, /* entity.raceId */'x', /* entity.snapshotAt */'2000-01-01', /* entity.modelTypeUsed */'x', /* entity.modelId */'x',
  /* entity.fallbackReason */'x', /* entity.betTypes */'x', /* entity.priorityMode */'x',
  /* entity.evThreshold */0, /* entity.winOddsMax */0, /* entity.winProbMin */0, /* entity.winRatioMin */0, /* entity.bestEv */0,
  /* entity.rejectionSummaryJson */'x', /* entity.signalSent */0, /* entity.createdAt */'2000-01-01'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM race_evaluations
  WHERE tenant_id = /* entity.tenantId */0
    AND race_id = /* entity.raceId */'x'
    AND snapshot_at = /* entity.snapshotAt */'2000-01-01'
)
