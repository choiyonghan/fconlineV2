-- "프리시즌 빼고 전부 합친 시트"를 시즌 칩 하나로 보고 싶다는 요청. seasons는 코드 입장에서
-- 그냥 [start_date, end_date] 범위일 뿐이고(Season.startInstant/endInstantExclusiveOrNull),
-- 모든 기록 API가 seasonId → 그 범위로만 스코프를 잡는다(SeasonRangeResolver) → 범위가
-- 시즌1 시작부터 시즌2 끝까지인 "전체" 행 하나를 넣으면 백엔드/프론트 코드 변경 없이
-- 시즌1+시즌2 합산 화면이 그대로 생긴다(report.js가 /api/v1/seasons 목록을 id 내림차순으로
-- 칩으로 그리므로 "전체"가 맨 앞에 붙는다).
--
-- 기존 시즌 행과 기간이 겹치는 건 의도된 것이다 — seasons는 "시간을 분할하는 파티션"이 아니고
-- 겹침을 가정하는 코드도 없다. 단 하나 지켜야 할 불변식은 V25/V28과 같은
-- "오늘 기준 진행 중인 시즌이 정확히 1개"이고, 전체는 어제(11/5)로 닫혀 있으므로 영향이 없다
-- (= 오늘 진행 중인 시즌은 프리시즌 하나뿐). 맨 아래에서 검증한다.
--
-- 프리시즌을 제외하는 이유는 요청 그대로다. 나중에 프리시즌이 닫히고 정규 시즌이 열리면
-- 그때 "전체"의 end_date를 늘리는 마이그레이션을 또 추가하면 된다.

SELECT setval(pg_get_serial_sequence('seasons', 'id'), COALESCE((SELECT max(id) FROM seasons), 1));

-- 2020-01-01 = 시즌1 start_date("쭉 한 시즌"을 덮으라고 넣은 충분히 이른 날짜, V15와 같은 관례),
-- 2026-11-05 = 시즌2 end_date(V28). 아래 검증 블록에서 실제 시즌 행과 일치하는지 확인한다.
INSERT INTO seasons (name, start_date, end_date) VALUES
    ('전체', '2020-01-01', '2026-11-05');

-- 전체 구간 스트릭 계산. V28 4)와 같은 이유로 앱이 스스로는 절대 채워주지 않는다
-- (MatchSyncFacade.recalculateStreaksForCurrentSeason는 "진행 중인 시즌"만 계산 — 전체는
-- 진행 중이 아니다) → 여기서 직접 재생(replay)해 둔다. 규칙/스코프는 V28과 동일하게
-- MatchDetailRepositoryImpl.findChronologicalResults + baseWhere / OpponentStreak.applyResult 이식.
DO $$
DECLARE
    sa_id    bigint;
    sa_from  timestamptz;
    sa_to    timestamptz;  -- 상한 제외(exclusive) — Season.endInstantExclusiveOrNull과 동일
    grp      record;
    mr       record;
    v_cur_w  integer; v_cur_l  integer; v_cur_wl integer; v_cur_ub integer;
    v_max_w  integer; v_max_l  integer; v_max_wl integer; v_max_ub integer;
    v_rows   integer := 0;
BEGIN
    SELECT id,
           (start_date::timestamp AT TIME ZONE 'Asia/Seoul'),
           ((end_date + 1)::timestamp AT TIME ZONE 'Asia/Seoul')
      INTO sa_id, sa_from, sa_to
      FROM seasons
     WHERE name = '전체';

    FOR grp IN
        SELECT md.ouid AS ouid, md.opponent_ouid AS opponent_ouid, m.match_type AS match_type
          FROM match_details md
          JOIN matches m ON m.match_id = md.match_id
         WHERE md.ouid IN (SELECT ouid FROM tracked_users)
           AND m.match_date >= sa_from
           AND m.match_date <  sa_to
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
               AND m.match_date >= sa_from
               AND m.match_date <  sa_to
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
        VALUES (grp.ouid, grp.opponent_ouid, grp.match_type, sa_id,
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

    RAISE NOTICE '전체 시즌 스트릭 계산 완료: %건', v_rows;
END $$;

-- 검증(깨지면 트랜잭션 전체 롤백)
DO $$
DECLARE
    v_count integer;
    v_name  text;
    v_start date;
    v_end   date;
    v_min   date;
    v_max   date;
    v_open  integer;
BEGIN
    -- 1) "전체" 행이 정확히 1개, 기간은 2020-01-01 ~ 2026-11-05
    SELECT count(*) INTO v_count FROM seasons WHERE name = '전체';
    IF v_count <> 1 THEN
        RAISE EXCEPTION '"전체" 시즌이 %개다 — 정확히 1개여야 한다', v_count;
    END IF;

    SELECT start_date, end_date INTO v_start, v_end FROM seasons WHERE name = '전체';
    IF v_start <> DATE '2020-01-01' OR v_end <> DATE '2026-11-05' THEN
        RAISE EXCEPTION '"전체" 기간이 % ~ %다 — 2020-01-01 ~ 2026-11-05여야 한다', v_start, v_end;
    END IF;

    -- 2) 그 기간이 실제로 "프리시즌 뺀 모든 시즌"을 덮는지 — 끝이 열린 시즌이 섞여 있으면
    --    max(end_date)가 그 시즌을 무시해버리므로 그것부터 막는다.
    SELECT count(*) INTO v_open
    FROM seasons WHERE name NOT IN ('전체', '프리시즌') AND end_date IS NULL;
    IF v_open <> 0 THEN
        RAISE EXCEPTION '끝이 열린(end_date IS NULL) 일반 시즌이 %개 있다 — "전체" 범위를 신뢰할 수 없다', v_open;
    END IF;

    SELECT min(start_date), max(end_date) INTO v_min, v_max
    FROM seasons WHERE name NOT IN ('전체', '프리시즌');

    -- 시즌은 운영 DB에 직접 넣는 시드 데이터(README "시드 데이터")라 새 스키마에는 아예 없다 —
    -- 그 경우(v_min IS NULL)엔 비교할 대상이 없으므로 건너뛴다.
    IF v_min IS NOT NULL AND (v_start <> v_min OR v_end <> v_max) THEN
        RAISE EXCEPTION '"전체"(% ~ %)가 일반 시즌 전체 범위(% ~ %)와 다르다', v_start, v_end, v_min, v_max;
    END IF;

    -- 3) 오늘 기준 진행 중인 시즌은 여전히 프리시즌 하나뿐이어야 한다(SeasonRepository.findCurrent)
    SELECT count(*) INTO v_count
    FROM seasons
    WHERE start_date <= DATE '2026-11-06'
      AND (end_date IS NULL OR end_date >= DATE '2026-11-06');

    IF v_count <> 1 THEN
        RAISE EXCEPTION '2026-11-06 기준 진행 중인 시즌이 %개다 — 정확히 1개여야 한다("전체"가 오늘을 덮고 있을 가능성)', v_count;
    END IF;

    SELECT name INTO v_name
    FROM seasons
    WHERE start_date <= DATE '2026-11-06'
      AND (end_date IS NULL OR end_date >= DATE '2026-11-06');

    IF v_name <> '프리시즌' THEN
        RAISE EXCEPTION '2026-11-06 기준 진행 중인 시즌이 "%"다 — "프리시즌"이어야 한다', v_name;
    END IF;
END $$;
