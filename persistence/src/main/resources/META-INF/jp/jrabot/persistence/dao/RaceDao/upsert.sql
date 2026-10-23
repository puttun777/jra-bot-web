INSERT INTO races (
  tenant_id, race_id, race_date, venue_code, race_no, kai, nichime, post_time, post_time_unknown, name, distance_m, created_at, updated_at
) VALUES (
  /* entity.tenantId */0, /* entity.raceId */'x', /* entity.raceDate */'2000-01-01', /* entity.venueCode */'x', /* entity.raceNo */0,
  /* entity.kai */0, /* entity.nichime */0, /* entity.postTime */'00:00:00', /* entity.postTimeUnknown */0, /* entity.name */'x',
  /* entity.distanceM */0, /* entity.createdAt */'2000-01-01', /* entity.updatedAt */'2000-01-01'
) AS new
ON DUPLICATE KEY UPDATE
  updated_at = IF(
    races.kai <=> new.kai AND races.nichime <=> new.nichime AND races.post_time <=> new.post_time
      AND races.post_time_unknown <=> new.post_time_unknown AND races.name <=> new.name AND races.distance_m <=> new.distance_m,
    races.updated_at, new.updated_at),
  kai = new.kai,
  nichime = new.nichime,
  post_time = new.post_time,
  post_time_unknown = new.post_time_unknown,
  name = new.name,
  distance_m = new.distance_m
