-- 서울쥐가 11/3부터 팀을 "레알마드리드"로 바꿨다(사용자 확인). V23에서 끝을 열어뒀던
-- 아스날 구간(10/11~)을 전날(11/2)로 닫고, 새 구간을 이어 붙인다.

UPDATE user_team_periods
SET end_date = '2026-11-02'
WHERE ouid = '1b8131906ebdd10fcb02b79367cc515a'
  AND team_name = '아스날'
  AND start_date = '2026-10-11'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('1b8131906ebdd10fcb02b79367cc515a', '레알마드리드', '2026-11-03', NULL);
