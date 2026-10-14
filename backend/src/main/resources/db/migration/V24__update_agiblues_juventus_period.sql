-- 아기블루스가 10/12부터 팀을 "유벤투스"로 바꿨다(사용자 확인). V15에서 끝을 열어뒀던
-- 파리생제르맹 구간(2020-01-01~)을 전날(10/11)로 닫고, 새 구간을 이어 붙인다.

UPDATE user_team_periods
SET end_date = '2026-10-11'
WHERE ouid = '2a27e3732d7ea05e453ac8a41f39b134'
  AND team_name = '파리생제르맹'
  AND start_date = '2020-01-01'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('2a27e3732d7ea05e453ac8a41f39b134', '유벤투스', '2026-10-12', NULL);
