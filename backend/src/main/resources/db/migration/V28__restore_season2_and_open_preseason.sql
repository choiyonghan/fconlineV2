-- V25에서 연 "시즌3"(2026-10-29~)은 잘못된 변경이었다(사용자 확인). 되돌리고 프리시즌을 연다:
--   1) 시즌2의 끝을 어제(2026-11-05)로 되돌린다 → 10/29~11/5 경기가 다시 시즌2로 집계된다
--   2) 시즌3 행을 삭제한다 — 존재하지 않았어야 하는 시즌이라 시즌 선택 목록에도 남기지 않는다
--   3) 오늘(2026-11-06)부터 "프리시즌"을 열어 진행 중인 시즌(active)으로 만든다
--   4) 시즌2 스트릭을 재계산한다 (아래 긴 주석 참고)
--
-- 코드가 기대하는 불변식은 V25와 같다: "오늘 기준 진행 중인 시즌"이 정확히 1개
-- (SeasonRepository.findCurrent가 Optional을 반환 — 2개면 조회 자체가 터진다). 맨 아래에서 검증한다.

-- 1) 시즌2를 어제까지로 다시 닫는다
UPDATE seasons
SET end_date = '2026-11-05'
WHERE name = '시즌2'
  AND (end_date IS NULL OR end_date < '2026-11-05');

-- 2) 시즌3 제거. opponent_streaks.season_id가 seasons를 FK로 참조하므로 스트릭 행을 먼저 지운다.
--    그 행들이 집계한 구간(10/29~11/5)은 이제 시즌2 소속이고 4)에서 시즌2 스트릭으로 다시
--    계산되므로 잃는 데이터는 없다.
DELETE FROM opponent_streaks
WHERE season_id IN (SELECT id FROM seasons WHERE name = '시즌3');

DELETE FROM seasons WHERE name = '시즌3';

-- 3) 프리시즌 개방. 시즌1/시즌2가 id를 명시해 직접 INSERT된 행이면 BIGSERIAL 시퀀스가 뒤처져
--    있을 수 있다(V25와 같은 이유) → 넣기 전에 현재 MAX(id)로 맞춘다.
SELECT setval(pg_get_serial_sequence('seasons', 'id'), COALESCE((SELECT max(id) FROM seasons), 1));

INSERT INTO seasons (name, start_date, end_date) VALUES
    ('프리시즌', '2026-11-06', NULL);

-- 4) 시즌2 스트릭 재계산.
--    스트릭은 동기화 때 "진행 중인 시즌"에 대해서만 재계산된다
--    (MatchSyncFacade.recalculateStreaksForCurrentSeason) → 이미 닫혀 있던 시즌2의 구간을
--    1)에서 늘려도 앱은 스스로 다시 계산하지 않고, 10/29~11/5 경기가 빠진 값이 영구히 남는다.
--    그래서 여기서 직접 재생(replay)한다. 규칙/스코프는 자바 구현을 그대로 옮긴 것이다:
--      - 순서/필터: MatchDetailRepositoryImpl.findChronologicalResults + baseWhere
--        (match_date 오름차순, 커스텀(40)에만 match_end_type = 0 조건. 공식(50)은 전부 포함)
--      - 카운터 갱신: OpponentStreak.applyResult (승/패/무 분기별 cur/max 갱신 규칙)
--    대상 ouid는 tracked_users 전체다 — 앱은 is_tracked = true만 계산하지만, 추적을 껐다 켠
--    유저의 기존 행이 낡은 값으로 남는 것보다 정확한 값을 채워두는 쪽이 안전하다.
--    변수명은 컬럼명(cur_win/max_win/...)과 겹치지 않게 접두사를 붙였다(plpgsql variable_conflict).
DO $$
DECLARE
    s2_id    bigint;
    s2_from  timestamptz;
    s2_to    timestamptz;  -- 상한 제외(exclusive) — Season.endInstantExclusiveOrNull과 동일
    grp      record;
    mr       record;
    v_cur_w  integer; v_cur_l  integer; v_cur_wl integer; v_cur_ub integer;
    v_max_w  integer; v_max_l  integer; v_max_wl integer; v_max_ub integer;
    v_rows   integer := 0;
BEGIN
    SELECT id,
           (start_date::timestamp AT TIME ZONE 'Asia/Seoul'),
           ((end_date + 1)::timestamp AT TIME ZONE 'Asia/Seoul')
      INTO s2_id, s2_from, s2_to
      FROM seasons
     WHERE name = '시즌2';

    IF s2_id IS NULL THEN
        -- 시즌은 운영 DB에 직접 넣는 시드 데이터(README "시드 데이터")라 새 스키마에는 없다.
        RAISE NOTICE '시즌2가 없어 스트릭 재계산을 건너뜁니다.';
        RETURN;
    END IF;

    FOR grp IN
        SELECT md.ouid AS ouid, md.opponent_ouid AS opponent_ouid, m.match_type AS match_type
          FROM match_details md
          JOIN matches m ON m.match_id = md.match_id
         WHERE md.ouid IN (SELECT ouid FROM tracked_users)
           AND m.match_date >= s2_from
           AND m.match_date <  s2_to
           AND (m.match_type <> 40 OR md.match_end_type = 0)
         GROUP BY md.ouid, md.opponent_ouid, m.match_type
    LOOP
        v_cur_w := 0; v_cur_l := 0; v_cur_wl := 0; v_cur_ub := 0;
        v_max_w := 0; v_max_l := 0; v_max_wl := 0; v_max_ub := 0;

        FOR mr IN
            SELECT md.match_result AS match_result
              FROM match_details md
              JOIN matches m ON m.match_id = md.match_id
             WHERE md.ouid = grp.ouid
               AND md.opponent_ouid = grp.opponent_ouid
               AND m.match_type = grp.match_type
               AND (m.match_type <> 40 OR md.match_end_type = 0)
               AND m.match_date >= s2_from
               AND m.match_date <  s2_to
             ORDER BY m.match_date, md.match_id
        LOOP
            IF mr.match_result = '승' THEN
                v_cur_w  := v_cur_w + 1;
                v_cur_ub := v_cur_ub + 1;
                v_cur_l  := 0;
                v_cur_wl := 0;
                v_max_w  := greatest(v_max_w, v_cur_w);
                v_max_ub := greatest(v_max_ub, v_cur_ub);
            ELSIF mr.match_result = '패' THEN
                v_cur_l  := v_cur_l + 1;
                v_cur_wl := v_cur_wl + 1;
                v_cur_w  := 0;
                v_cur_ub := 0;
                v_max_l  := greatest(v_max_l, v_cur_l);
                v_max_wl := greatest(v_max_wl, v_cur_wl);
            ELSE  -- '무'
                v_cur_ub := v_cur_ub + 1;
                v_cur_wl := v_cur_wl + 1;
                v_cur_w  := 0;
                v_cur_l  := 0;
                v_max_ub := greatest(v_max_ub, v_cur_ub);
                v_max_wl := greatest(v_max_wl, v_cur_wl);
            END IF;
        END LOOP;

        INSERT INTO opponent_streaks (
            ouid, opponent_ouid, match_type, season_id,
            cur_win, cur_lose, cur_winless, cur_unbeaten,
            max_win, max_lose, max_winless, max_unbeaten, updated_at)
        VALUES (grp.ouid, grp.opponent_ouid, grp.match_type, s2_id,
            v_cur_w, v_cur_l, v_cur_wl, v_cur_ub,
            v_max_w, v_max_l, v_max_wl, v_max_ub, now())
        ON CONFLICT (ouid, opponent_ouid, match_type, season_id) DO UPDATE SET
            cur_win      = EXCLUDED.cur_win,
            cur_lose     = EXCLUDED.cur_lose,
            cur_winless  = EXCLUDED.cur_winless,
            cur_unbeaten = EXCLUDED.cur_unbeaten,
            max_win      = EXCLUDED.max_win,
            max_lose     = EXCLUDED.max_lose,
            max_winless  = EXCLUDED.max_winless,
            max_unbeaten = EXCLUDED.max_unbeaten,
            updated_at   = EXCLUDED.updated_at;

        v_rows := v_rows + 1;
    END LOOP;

    RAISE NOTICE '시즌2 스트릭 재계산 완료: %건', v_rows;
END $$;

-- 검증(깨지면 트랜잭션 전체 롤백)
DO $$
DECLARE
    v_count integer;
    v_name  text;
    v_left  integer;
    v_end   date;
BEGIN
    SELECT count(*) INTO v_count
    FROM seasons
    WHERE start_date <= DATE '2026-11-06'
      AND (end_date IS NULL OR end_date >= DATE '2026-11-06');

    IF v_count <> 1 THEN
        RAISE EXCEPTION '2026-11-06 기준 진행 중인 시즌이 %개다 — 정확히 1개여야 한다', v_count;
    END IF;

    SELECT name INTO v_name
    FROM seasons
    WHERE start_date <= DATE '2026-11-06'
      AND (end_date IS NULL OR end_date >= DATE '2026-11-06');

    IF v_name <> '프리시즌' THEN
        RAISE EXCEPTION '2026-11-06 기준 진행 중인 시즌이 "%"다 — "프리시즌"이어야 한다', v_name;
    END IF;

    SELECT count(*) INTO v_left FROM seasons WHERE name = '시즌3';
    IF v_left <> 0 THEN
        RAISE EXCEPTION '시즌3가 %개 남아있다 — 삭제되어야 한다', v_left;
    END IF;

    -- 시즌2가 있는 운영 DB에서만: 시즌2의 끝이 프리시즌 시작 바로 전날이어야 한다(빈틈/겹침 없음)
    SELECT end_date INTO v_end FROM seasons WHERE name = '시즌2';
    IF FOUND AND v_end IS DISTINCT FROM DATE '2026-11-05' THEN
        RAISE EXCEPTION '시즌2의 end_date가 2026-11-05가 아니다: %', v_end;
    END IF;
END $$;
