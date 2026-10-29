-- 2026-10-29(KST)부터 시즌3이 시작됐다 — 시즌2는 전날(10/28)로 닫는다(사용자 확인).
-- seasons는 원래 최초 1회 직접 넣는 시드 데이터지만(README "시드 데이터" 참고), 시즌 경계는
-- 모든 집계(스트릭/대시보드·인사이트 스냅샷/AI 질문)의 스코프를 결정하는 값이라 변경 이력이
-- 필요하다 → user_team_periods 기간 변경(V18~V24)과 같은 방식으로 마이그레이션에 남긴다.
--
-- 코드가 기대하는 불변식: "오늘 기준 진행 중인 시즌"이 정확히 1개다
-- (SeasonRepository.findCurrent가 Optional을 반환 — 2개가 되면 조회 자체가 터진다).
-- 아래 UPDATE/INSERT 뒤에 그 불변식을 직접 검증한다(깨지면 트랜잭션 전체 롤백).

UPDATE seasons
SET end_date = '2026-10-28'
WHERE name = '시즌2'
  AND (end_date IS NULL OR end_date > '2026-10-28');

-- 시즌1/시즌2는 운영 DB에 직접 INSERT된 행이라 id를 명시해 넣었다면 BIGSERIAL 시퀀스가
-- 뒤처져 있을 수 있다(그 상태로 INSERT하면 PK 충돌) → 넣기 전에 현재 MAX(id)로 맞춘다.
SELECT setval(pg_get_serial_sequence('seasons', 'id'), COALESCE((SELECT max(id) FROM seasons), 1));

INSERT INTO seasons (name, start_date, end_date) VALUES
    ('시즌3', '2026-10-29', NULL);

DO $$
DECLARE
    current_count integer;
BEGIN
    SELECT count(*) INTO current_count
    FROM seasons
    WHERE start_date <= DATE '2026-10-29'
      AND (end_date IS NULL OR end_date >= DATE '2026-10-29');

    IF current_count <> 1 THEN
        RAISE EXCEPTION '2026-10-29 기준 진행 중인 시즌이 %개다 — 정확히 1개여야 한다(시즌2 종료 처리 실패 가능성)', current_count;
    END IF;
END $$;
