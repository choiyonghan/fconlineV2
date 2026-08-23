-- "전체 선수 스탯" 그리드를 슛정확/패스/드리블/공중볼/평점까지 보여주기 위해
-- squad_entries에 player[].status의 나머지 지표를 추가로 정규화한다.
-- 기존 goal/assist/save_count/tackle/intercept/block과 같은 패턴 — Nexon 응답 필드명 그대로,
-- 접두사 없이 저장한다.
--
-- 백필: match_details.player_squad(V9에서 추가한 원본 JSONB 백업)에 이미 이 값들이 전부
-- 들어있으므로, Nexon API를 다시 호출하지 않고 DB 안에서 그대로 복구한다. sp_id로 매칭한다
-- (한 매치 안에서 spId는 유일).

ALTER TABLE squad_entries
    ADD COLUMN shoot_total     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN effective_shoot INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pass_try        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pass_success    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN dribble_try     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN dribble_success INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN aerial_try      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN aerial_success  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rating          DOUBLE PRECISION;

UPDATE squad_entries se
SET shoot_total     = COALESCE((x.elem -> 'status' ->> 'shoot')::int, 0),
    effective_shoot = COALESCE((x.elem -> 'status' ->> 'effectiveShoot')::int, 0),
    pass_try        = COALESCE((x.elem -> 'status' ->> 'passTry')::int, 0),
    pass_success    = COALESCE((x.elem -> 'status' ->> 'passSuccess')::int, 0),
    dribble_try     = COALESCE((x.elem -> 'status' ->> 'dribbleTry')::int, 0),
    dribble_success = COALESCE((x.elem -> 'status' ->> 'dribbleSuccess')::int, 0),
    aerial_try      = COALESCE((x.elem -> 'status' ->> 'aerialTry')::int, 0),
    aerial_success  = COALESCE((x.elem -> 'status' ->> 'aerialSuccess')::int, 0),
    rating          = (x.elem -> 'status' ->> 'spRating')::double precision
FROM (
    SELECT md.id AS match_detail_id, e.value AS elem, e.value ->> 'spId' AS sp_id
    FROM match_details md
    CROSS JOIN LATERAL jsonb_array_elements(md.player_squad) AS e(value)
    WHERE md.player_squad IS NOT NULL
) x
WHERE se.match_detail_id = x.match_detail_id
  AND se.sp_id = x.sp_id;
