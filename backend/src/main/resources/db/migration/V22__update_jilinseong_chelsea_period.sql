-- 지린성에사는욱구가 10/11부터 팀을 "첼시"로 바꿨다(사용자 확인). V21에서 끝을 열어뒀던
-- 아스날 구간(10/3~)을 전날(10/10)로 닫고, 새 구간을 이어 붙인다.

UPDATE user_team_periods
SET end_date = '2026-10-10'
WHERE ouid = '1894adb89b4a7953381bdd5671ce7610'
  AND team_name = '아스날'
  AND start_date = '2026-10-03'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('1894adb89b4a7953381bdd5671ce7610', '첼시', '2026-10-11', NULL);
