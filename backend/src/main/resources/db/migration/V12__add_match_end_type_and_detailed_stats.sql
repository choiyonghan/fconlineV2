-- 요청받은 4가지를 한 마이그레이션으로 처리한다:
--   1) match_end_type/system_pause 정규화 컬럼 추가 — "matchEndType=0(정상 종료)인 경기만
--      쓴다"는 요청의 필터 기준이 될 컬럼. Nexon 응답에서 matchDetail 객체 안에 있다고 보고
--      진행한다(offside처럼 검증 전 필드라 값이 이상하면 여기 주석과 매핑을 다시 봐야 함).
--   2) match_details에 패스 세부 유형(숏/롱/바운싱롭/드리븐그라운드/스루/로빙스루) 시도·성공,
--      수비 블락 시도·성공 컬럼 추가 — pass/defence 객체에서 지금까지 매핑 안 하던 필드들.
--   3) squad_entries에 드리블 거리(dribble_distance) 추가.
--   4) V9/V10이 남겨둔 raw_participant(JSONB, 참가자 원소 전체 백업)/player_squad(V9,
--      선수 배열 백업)에서 위 신규 컬럼들을 즉시 백필한다 — V11과 같은 패턴(COALESCE + 캐스팅),
--      Nexon API 재호출 없이 DB 안에서 복구한다.
--
-- 한계: raw_participant는 V10(이 마이그레이션보다 나중에 추가됨) 이후 동기화된 경기에만
-- 있다. 그보다 오래된 경기는 raw_participant가 NULL이라 match_end_type을 백필할 수 없고,
-- 그 결과 "matchEndType=0만" 필터에서 조용히 제외된다(0이라고 확인할 방법이 없으니 안전한
-- 쪽으로 뺀다) — 이후 동기화되는 경기부터는 정상적으로 채워진다.

ALTER TABLE match_details
    ADD COLUMN match_end_type              INTEGER,
    ADD COLUMN system_pause                INTEGER,
    ADD COLUMN short_pass_success          INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN long_pass_try               INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN long_pass_success           INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN bouncing_lob_pass_try       INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN bouncing_lob_pass_success   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN driven_ground_pass_try      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN driven_ground_pass_success  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN lobbed_through_pass_try     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN lobbed_through_pass_success INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN block_try                   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN block_success               INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN match_details.match_end_type IS
    '0=정상 종료로 보고 필터 기준으로 쓴다. Nexon matchDetail.matchEndType 그대로 — 값 의미는 0(정상) 외 미확정.';

ALTER TABLE squad_entries
    ADD COLUMN dribble_distance INTEGER NOT NULL DEFAULT 0;

UPDATE match_details
SET match_end_type              = (raw_participant -> 'matchDetail' ->> 'matchEndType')::int,
    system_pause                = (raw_participant -> 'matchDetail' ->> 'systemPause')::int,
    short_pass_success          = COALESCE((raw_participant -> 'pass' ->> 'shortPassSuccess')::int, 0),
    long_pass_try               = COALESCE((raw_participant -> 'pass' ->> 'longPassTry')::int, 0),
    long_pass_success           = COALESCE((raw_participant -> 'pass' ->> 'longPassSuccess')::int, 0),
    bouncing_lob_pass_try       = COALESCE((raw_participant -> 'pass' ->> 'bouncingLobPassTry')::int, 0),
    bouncing_lob_pass_success   = COALESCE((raw_participant -> 'pass' ->> 'bouncingLobPassSuccess')::int, 0),
    driven_ground_pass_try      = COALESCE((raw_participant -> 'pass' ->> 'drivenGroundPassTry')::int, 0),
    driven_ground_pass_success  = COALESCE((raw_participant -> 'pass' ->> 'drivenGroundPassSuccess')::int, 0),
    lobbed_through_pass_try     = COALESCE((raw_participant -> 'pass' ->> 'lobbedThroughPassTry')::int, 0),
    lobbed_through_pass_success = COALESCE((raw_participant -> 'pass' ->> 'lobbedThroughPassSuccess')::int, 0),
    block_try                   = COALESCE((raw_participant -> 'defence' ->> 'blockTry')::int, 0),
    block_success               = COALESCE((raw_participant -> 'defence' ->> 'blockSuccess')::int, 0)
WHERE raw_participant IS NOT NULL;

UPDATE squad_entries se
SET dribble_distance = COALESCE((x.elem -> 'status' ->> 'dribbleDistance')::int, 0)
FROM (
    SELECT md.id AS match_detail_id, e.value AS elem, e.value ->> 'spId' AS sp_id
    FROM match_details md
    CROSS JOIN LATERAL jsonb_array_elements(md.player_squad) AS e(value)
    WHERE md.player_squad IS NOT NULL
) x
WHERE se.match_detail_id = x.match_detail_id
  AND se.sp_id = x.sp_id;
