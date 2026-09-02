-- 내눈을가져가가 8/31부터 팀을 "잉글랜드"로 바꿨다(사용자 확인). V15에서 끝을 열어뒀던
-- 인터밀란 구간(8/12~)을 전날(8/30)로 닫고, 새 구간을 이어 붙인다.

UPDATE user_team_periods
SET end_date = '2026-08-30'
WHERE ouid = '3187c800228a6e98e07fcd2a9cd97b93'
  AND team_name = '인터밀란'
  AND start_date = '2026-08-12'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('3187c800228a6e98e07fcd2a9cd97b93', '잉글랜드', '2026-08-31', NULL);
