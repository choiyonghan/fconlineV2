-- 서울쥐가 9/4부터 팀을 "맨체스터유나이티드"로 바꿨다(사용자 확인). V15에서 끝을 열어뒀던
-- AS로마 구간(8/12~)을 전날(9/3)로 닫고, 새 구간을 이어 붙인다.

UPDATE user_team_periods
SET end_date = '2026-09-03'
WHERE ouid = '1b8131906ebdd10fcb02b79367cc515a'
  AND team_name = 'AS로마'
  AND start_date = '2026-08-12'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('1b8131906ebdd10fcb02b79367cc515a', '맨체스터유나이티드', '2026-09-04', NULL);
