-- shootDetail[]의 나머지 원본 필드(좌표/득점 선수/어시스트/임대여부 등)를 추가로 보존한다.
-- V3에서는 분포 집계(유형별/시간대별)에 필요한 최소 필드(shoot_type/result/goal_time_minutes/period)만
-- 저장했는데, 좌표 기반 히트맵/득점 선수·어시스트 체인 분석까지 지원하려면 원본을 통째로 남겨야 한다.

ALTER TABLE shoot_events
    ADD COLUMN sp_id        VARCHAR(32),
    ADD COLUMN sp_grade     INTEGER,
    ADD COLUMN sp_level     INTEGER,
    ADD COLUMN loaned       BOOLEAN,
    ADD COLUMN x            DOUBLE PRECISION,
    ADD COLUMN y            DOUBLE PRECISION,
    ADD COLUMN assist       BOOLEAN,
    ADD COLUMN assist_sp_id VARCHAR(32),
    ADD COLUMN assist_x     DOUBLE PRECISION,
    ADD COLUMN assist_y     DOUBLE PRECISION,
    ADD COLUMN hit_post     BOOLEAN,
    ADD COLUMN in_penalty   BOOLEAN;

-- 득점 선수(spId) 기준 조회(예: 선수별 득점 히트맵) 지원.
CREATE INDEX idx_shoot_events_sp_id ON shoot_events (sp_id) WHERE result = 'GOAL';
