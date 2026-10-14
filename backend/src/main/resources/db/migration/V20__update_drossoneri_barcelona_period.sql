-- D로쏘네리가 9/20부터 팀을 "바르셀로나"로 바꿨다(사용자 확인). V15에서 끝을 열어뒀던
-- AC밀란 구간(2020-01-01~)을 전날(9/19)로 닫고, 새 구간을 이어 붙인다.

UPDATE user_team_periods
SET end_date = '2026-09-19'
WHERE ouid = 'c4f8613f9995dc79c5413cc61fa1d6f2'
  AND team_name = 'AC밀란'
  AND start_date = '2020-01-01'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('c4f8613f9995dc79c5413cc61fa1d6f2', '바르셀로나', '2026-09-20', NULL);
