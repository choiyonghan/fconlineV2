-- 매치 상세/최근 경기 목록에 "이 시점에 이 유저가 쓰던 팀"을 같이 보여달라는 요청.
-- Nexon 응답엔 이 정보가 없어서(스쿼드 구성만으로 "팀"을 자동 추론할 방법이 없다)
-- seasons 테이블과 같은 패턴(기간 + null=진행중)으로 직접 관리하는 테이블을 새로 둔다.
-- 데이터는 사용자가 직접 조사해서 알려주는 대로 후속 마이그레이션(INSERT)으로 채운다.

CREATE TABLE user_team_periods (
    id         BIGSERIAL    PRIMARY KEY,
    ouid       VARCHAR(64)  NOT NULL REFERENCES tracked_users (ouid) ON DELETE CASCADE,
    team_name  VARCHAR(100) NOT NULL,
    start_date DATE         NOT NULL,
    end_date   DATE,  -- null = 현재까지 계속 쓰는 중
    CONSTRAINT ck_user_team_periods_date_range CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_user_team_periods_ouid_start ON user_team_periods (ouid, start_date);
