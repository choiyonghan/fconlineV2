-- Match(얇은 애그리게잇)와 MatchDetail(참가자 1명 시점의 무거운 애그리게잇)을 분리한다.
-- match_type은 정수(40=CUSTOM, 50=OFFICIAL)로 저장하고 MatchTypeConverter가 enum과 매핑한다.
-- match_result는 v1 데이터와의 호환을 위해 '승'/'무'/'패' 문자열을 그대로 쓴다.

CREATE TABLE matches (
    match_id   VARCHAR(64) PRIMARY KEY,
    match_date TIMESTAMPTZ NOT NULL,
    match_type INTEGER     NOT NULL,
    CONSTRAINT ck_matches_match_type CHECK (match_type IN (40, 50))
);

CREATE INDEX idx_matches_type_date ON matches (match_type, match_date);

CREATE TABLE match_details (
    id                    BIGSERIAL   PRIMARY KEY,
    match_id              VARCHAR(64) NOT NULL REFERENCES matches (match_id) ON DELETE CASCADE,
    ouid                  VARCHAR(64) NOT NULL,
    opponent_ouid         VARCHAR(64) NOT NULL,
    opponent_nickname     VARCHAR(100) NOT NULL,
    match_result          VARCHAR(10) NOT NULL,
    controller            VARCHAR(20),
    average_rating        DOUBLE PRECISION,
    goals_for             INTEGER,
    goals_against         INTEGER,
    shoot_total           INTEGER,
    effective_shoot       INTEGER,
    goal_in_penalty       INTEGER,
    goal_out_penalty      INTEGER,
    shoot_heading         INTEGER,
    own_goal              INTEGER,
    possession            INTEGER,
    pass_try              INTEGER,
    pass_success          INTEGER,
    short_pass_try        INTEGER,
    through_pass_try      INTEGER,
    through_pass_success  INTEGER,
    tackle_try            INTEGER,
    tackle_success        INTEGER,
    foul                  INTEGER,
    yellow_cards          INTEGER,
    red_cards             INTEGER,
    -- v1 버그(analysis 6.3): 컬럼이 없어 프론트가 항상 0을 표시했다. 여기서는 컬럼을 두고
    -- 실 Nexon 응답 검증 후 정확히 채운다 (검증 실패 시 이 컬럼과 관련 응답 필드를 제거할 것).
    offside                INTEGER,
    CONSTRAINT ck_match_details_result CHECK (match_result IN ('승', '무', '패')),
    CONSTRAINT uq_match_details_match_ouid UNIQUE (match_id, ouid)
);

CREATE INDEX idx_match_details_ouid ON match_details (ouid);
CREATE INDEX idx_match_details_ouid_opponent ON match_details (ouid, opponent_ouid);
