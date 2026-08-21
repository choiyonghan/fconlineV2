-- v1 버그(analysis 6.2): 유니크 키가 (ouid, opponent_ouid)뿐이라 커스텀/공식 매치의
-- 스트릭 집계가 같은 행을 서로 다른 범위로 덮어썼다. match_type/season_id를 키에 포함해
-- 구조적으로 차단한다.

CREATE TABLE opponent_streaks (
    id            BIGSERIAL   PRIMARY KEY,
    ouid          VARCHAR(64) NOT NULL,
    opponent_ouid VARCHAR(64) NOT NULL,
    match_type    INTEGER     NOT NULL,
    season_id     BIGINT      NOT NULL REFERENCES seasons (id),
    cur_win       INTEGER     NOT NULL DEFAULT 0,
    cur_lose      INTEGER     NOT NULL DEFAULT 0,
    cur_winless   INTEGER     NOT NULL DEFAULT 0,
    cur_unbeaten  INTEGER     NOT NULL DEFAULT 0,
    max_win       INTEGER     NOT NULL DEFAULT 0,
    max_lose      INTEGER     NOT NULL DEFAULT 0,
    max_winless   INTEGER     NOT NULL DEFAULT 0,
    max_unbeaten  INTEGER     NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_opponent_streaks_match_type CHECK (match_type IN (40, 50)),
    CONSTRAINT uq_opponent_streaks UNIQUE (ouid, opponent_ouid, match_type, season_id)
);
