-- 내혀를가져가가 11/5부터 팀을 "대한민국"으로 바꿨다(사용자 확인). V15에서 끝을 열어뒀던
-- 맨체스터 유나이티드 구간(2020-01-01~ — "계속 한 팀"이라 전체 기간을 덮는 시작일)을
-- 전날(11/4)로 닫고, 새 구간을 이어 붙인다.
--
-- 팀 이름은 사용자가 말한 그대로 "대한민국"으로 넣는다 — 서울쥐의 V15 "한국"과 표기가
-- 다르지만 team_name은 화면에 그대로 찍히는 자유 문자열(로고/코드 매핑 없음)이라
-- 과거 행을 건드려 표기를 통일할 이유가 없다.

UPDATE user_team_periods
SET end_date = '2026-11-04'
WHERE ouid = '4b3bb4908597dd3cedb84fd24f5bebd1'
  AND team_name = '맨체스터 유나이티드'
  AND start_date = '2020-01-01'
  AND end_date IS NULL;

INSERT INTO user_team_periods (ouid, team_name, start_date, end_date) VALUES
    ('4b3bb4908597dd3cedb84fd24f5bebd1', '대한민국', '2026-11-05', NULL);
