-- v1의 4곳에 흩어진 하드코딩(닉네임 목록 4벌, 시즌 기준일 3벌)을 대체하는 두 테이블.
-- analysis 6.4, 6.8, 6.13-2 참고.

CREATE TABLE tracked_users (
    ouid          VARCHAR(64)  PRIMARY KEY,
    nickname      VARCHAR(100) NOT NULL,
    is_tracked    BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order INTEGER      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tracked_users_nickname UNIQUE (nickname)
);

CREATE TABLE seasons (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(50) NOT NULL,
    start_date DATE        NOT NULL,
    end_date   DATE,  -- null = 진행 중인 시즌
    CONSTRAINT ck_seasons_date_range CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_seasons_start_date ON seasons (start_date);
