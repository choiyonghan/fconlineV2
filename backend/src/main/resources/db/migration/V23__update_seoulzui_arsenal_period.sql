-- 서울쥐가 10/11부터 팀을 "아스날"로 바꿨다(사용자 확인). V19에서 끝을 열어뒀던
-- AC밀란 구간(9/25~)을 전날(10/10)로 닫고, 새 구간을 이어 붙인다.

UPDATE user_team_periods
SET end_date = '2026-10-10'
WHERE ouid = '1b8131906ebdd10fcb02b79367cc515a'
  AND team_name = 'AC밀란'
  AND start_date = '2026-09-25'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('1b8131906ebdd10fcb02b79367cc515a', '아스날', '2026-10-11', NULL);
