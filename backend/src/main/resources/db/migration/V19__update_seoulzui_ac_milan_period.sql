-- 서울쥐가 9/25부터 팀을 "AC밀란"으로 바꿨다(사용자 확인). V18에서 끝을 열어뒀던
-- 맨체스터유나이티드 구간(9/4~)을 전날(9/24)로 닫고, 새 구간을 이어 붙인다.

UPDATE user_team_periods
SET end_date = '2026-09-24'
WHERE ouid = '1b8131906ebdd10fcb02b79367cc515a'
  AND team_name = '맨체스터유나이티드'
  AND start_date = '2026-09-04'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('1b8131906ebdd10fcb02b79367cc515a', 'AC밀란', '2026-09-25', NULL);
