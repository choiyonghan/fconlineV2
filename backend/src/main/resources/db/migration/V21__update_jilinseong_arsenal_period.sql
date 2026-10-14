-- 지린성에사는욱구가 10/3부터 팀을 "아스날"로 바꿨다(사용자 확인). V15에서 끝을 열어뒀던
-- 바르셀로나 구간(8/20~)을 전날(10/2)로 닫고, 새 구간을 이어 붙인다.

UPDATE user_team_periods
SET end_date = '2026-10-02'
WHERE ouid = '1894adb89b4a7953381bdd5671ce7610'
  AND team_name = '바르셀로나'
  AND start_date = '2026-08-20'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('1894adb89b4a7953381bdd5671ce7610', '아스날', '2026-10-03', NULL);
