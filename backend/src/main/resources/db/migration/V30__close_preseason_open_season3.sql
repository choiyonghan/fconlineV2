-- 프리시즌을 오늘(2026-11-11)로 닫고 내일(2026-11-12)부터 "시즌3"를 진행 중인 시즌으로 연다.
--
-- "진행 중인 시즌"은 저장된 플래그가 아니라 KST 오늘 날짜로 그때그때 판정된다
-- (SeasonRepository.findCurrent(LocalDate.now(KstZone.ID)) / Season.isCurrent) → 이 마이그레이션이
-- 오늘 배포돼도 11/11까지는 프리시즌, 11/12 00:00(KST)부터 자동으로 시즌3가 current가 된다.
-- 프론트는 current 시즌 칩을 기본 선택하므로(+page.svelte) 따로 손댈 것이 없다.
-- (단 /api/v1/seasons 응답은 Redis TTL 30분 캐시라 자정 직후 최대 30분은 프리시즌이 기본으로 보일 수 있다.)
--
-- 스트릭: 시즌3는 아직 경기가 없으므로 재계산할 게 없고, 11/12부터 동기화가 알아서 채운다.
-- 프리시즌 스트릭은 11/11까지 동기화로 계속 갱신된다. 11/11 막판 경기가 11/12 첫 동기화에서
-- 들어오면 그 경기의 프리시즌 스트릭은 갱신되지 않는다(진행 중인 시즌만 재계산) — 감수한다.
--
-- "전체"(V29)는 건드리지 않는다: 시즌3는 진행 중(end_date NULL)이라 "전체"를 늘리면
-- 진행 중인 시즌이 2개가 되고, 범위 하나로는 프리시즌만 빼는 것도 불가능하다.

-- 1) 프리시즌을 오늘까지로 닫는다
UPDATE seasons
SET end_date = '2026-11-11'
WHERE name = '프리시즌'
  AND end_date IS NULL;

-- 2) 시즌3 개방 (V25/V28과 같은 이유로 시퀀스를 먼저 맞춘다)
SELECT setval(pg_get_serial_sequence('seasons', 'id'), COALESCE((SELECT max(id) FROM seasons), 1));

INSERT INTO seasons (name, start_date, end_date) VALUES
    ('시즌3', '2026-11-12', NULL);

-- 검증(깨지면 트랜잭션 전체 롤백): 오늘은 프리시즌, 내일부터는 시즌3가 "정확히 1개"의 진행 중인 시즌이어야 한다
DO $$
DECLARE
    v_ref   date;
    v_want  text;
    v_count integer;
    v_name  text;
BEGIN
    FOR v_ref, v_want IN
        SELECT * FROM (VALUES (DATE '2026-11-11', '프리시즌'), (DATE '2026-11-12', '시즌3')) AS t(ref, want)
    LOOP
        SELECT count(*), min(name) INTO v_count, v_name
        FROM seasons
        WHERE start_date <= v_ref
          AND (end_date IS NULL OR end_date >= v_ref);

        -- 시즌은 운영 DB 시드 데이터라 새 스키마엔 프리시즌이 없다 → 그 날짜는 비교 대상이 없을 수 있다
        IF v_want = '프리시즌' AND NOT EXISTS (SELECT 1 FROM seasons WHERE name = '프리시즌') THEN
            CONTINUE;
        END IF;

        IF v_count <> 1 THEN
            RAISE EXCEPTION '% 기준 진행 중인 시즌이 %개다 — 정확히 1개여야 한다', v_ref, v_count;
        END IF;
        IF v_name <> v_want THEN
            RAISE EXCEPTION '% 기준 진행 중인 시즌이 "%"다 — "%"이어야 한다', v_ref, v_name, v_want;
        END IF;
    END LOOP;

    IF (SELECT count(*) FROM seasons WHERE name = '시즌3') <> 1 THEN
        RAISE EXCEPTION '시즌3가 정확히 1개가 아니다';
    END IF;
END $$;
