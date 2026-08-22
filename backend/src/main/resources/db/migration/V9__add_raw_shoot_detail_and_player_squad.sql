-- v1(public 스키마)처럼 shootDetail[]/player[] 원본 배열을 JSONB로 통째로 보존한다.
-- shoot_events/squad_entries(정규화 테이블)는 그대로 두고 집계 쿼리에 계속 쓴다 — 이 두 컬럼은
-- "아직 매핑 안 한 필드, 앞으로 Nexon 응답에 추가될 필드"까지 재동기화 없이 나중에 꺼내 쓰기 위한
-- 원본 백업이다. goalsAgainst/save처럼 매핑 버그가 또 나와도 이 원본으로 DB 안에서 바로 백필할 수 있다.

ALTER TABLE match_details
    ADD COLUMN shoot_detail JSONB,
    ADD COLUMN player_squad JSONB;
