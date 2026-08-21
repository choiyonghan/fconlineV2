-- match_details.shoot_detail / player_squad (v1의 JSON 컬럼)을 정규화한 자식 테이블.
-- 득점 유형별/시간대별 분포, TOP3 선수 집계가 QueryDSL GROUP BY로 DB에서 끝나도록 한다
-- (v1은 클라이언트가 JSON을 순회하며 집계했다 — analysis 6.10/7.3).

CREATE TABLE shoot_events (
    id                BIGSERIAL   PRIMARY KEY,
    match_detail_id   BIGINT      NOT NULL REFERENCES match_details (id) ON DELETE CASCADE,
    shoot_type        VARCHAR(32) NOT NULL,
    result            VARCHAR(16) NOT NULL,
    goal_time_minutes INTEGER,
    period            INTEGER
);

CREATE INDEX idx_shoot_events_match_detail ON shoot_events (match_detail_id);
-- 득점 시간대 분포 집계 전용 (result='GOAL'인 행만 대상)
CREATE INDEX idx_shoot_events_goal_time ON shoot_events (goal_time_minutes) WHERE result = 'GOAL';

CREATE TABLE squad_entries (
    id               BIGSERIAL   PRIMARY KEY,
    match_detail_id  BIGINT      NOT NULL REFERENCES match_details (id) ON DELETE CASCADE,
    sp_id            VARCHAR(32) NOT NULL,
    sp_position      INTEGER     NOT NULL,
    goal             INTEGER     NOT NULL DEFAULT 0,
    assist           INTEGER     NOT NULL DEFAULT 0,
    save_count       INTEGER     NOT NULL DEFAULT 0,
    tackle           INTEGER     NOT NULL DEFAULT 0,
    intercept        INTEGER     NOT NULL DEFAULT 0,
    block            INTEGER     NOT NULL DEFAULT 0,
    -- v1은 spPosition===28을 매직넘버로 직접 비교했다(analysis 6.8) — 적재 시점에 의미를 확정한다.
    substitute       BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_squad_entries_match_detail ON squad_entries (match_detail_id);
-- TOP3 선수 집계 GROUP BY 대상
CREATE INDEX idx_squad_entries_sp_id ON squad_entries (sp_id);
