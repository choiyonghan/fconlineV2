-- V12에서 dribble_distance를 player_squad -> status -> 'dribbleDistance'로 백필했는데,
-- 실제 Nexon 필드명은 'dribbleDistance'가 아니라 'dribble'이었다(사용자가 직접 확인) —
-- V12가 심어둔 값은 전부 0이었을 것이다. 같은 패턴으로 올바른 필드명에서 다시 백필한다.
-- 단위는 야드 그대로 저장한다(미터 환산은 표시 시점에 한다 — averageRating 5->10점 변환과 동일 관례).

UPDATE squad_entries se
SET dribble_distance = COALESCE((x.elem -> 'status' ->> 'dribble')::int, 0)
FROM (
    SELECT md.id AS match_detail_id, e.value AS elem, e.value ->> 'spId' AS sp_id
    FROM match_details md
    CROSS JOIN LATERAL jsonb_array_elements(md.player_squad) AS e(value)
    WHERE md.player_squad IS NOT NULL
) x
WHERE se.match_detail_id = x.match_detail_id
  AND se.sp_id = x.sp_id;
