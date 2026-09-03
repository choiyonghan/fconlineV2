package com.fconline.app.search.controller;

import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.MatchShotsResponse;
import com.fconline.app.record.dto.MatchSquadEntryResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.app.search.dto.SearchMatchStatsResponse;
import com.fconline.app.search.facade.SearchFacade;
import com.fconline.domain.match.vo.MatchType;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 추적 대상 9명이 아닌 임의 닉네임을 검색하는 화면(site-root/search.html)용 API. 전부 DB를
 * 거치지 않고 Nexon을 실시간 조회한다 — RecordController(/api/v1/records/*)와 경로를 분리해서
 * "이 API는 sync 배치가 채워둔 DB가 아니라 그 자리에서 Nexon을 호출한다"는 점을 URL만 보고도
 * 구분할 수 있게 한다(SearchFacade 클래스 주석 참고).
 *
 * <p>하위 경로 이름은 RecordController와 의도적으로 동일하게 맞췄다(요청 — SSR로 한 번에 묶어
 * 던지지 않고, 프론트가 report.js처럼 화면마다 API를 따로 호출하는 CSR 방식으로) — search.js가
 * report.js의 Promise.all 패턴을 그대로 재사용할 수 있게, 같은 응답 타입(OverallRecordResponse/
 * ShotHeatmapResponse/...)을 그대로 쓴다.
 */
@RestController
@RequestMapping("/api/v1/search/players")
public class SearchController {

    private final SearchFacade searchFacade;

    public SearchController(SearchFacade searchFacade) {
        this.searchFacade = searchFacade;
    }

    /**
     * 공통 파라미터: nickname(필수), matchType(필수), limit(선택, 기본 15, 최대 30 — 몇 경기까지
     * 조회할지). Nexon을 매치당 1번씩 호출하므로 limit이 크면 응답이 그만큼 오래 걸린다
     * (SearchFacade 클래스 주석 참고). 아래 API들은 전부 같은 (nickname, matchType, limit)
     * 조합이면 SearchMatchDetailCache를 공유하므로, 같은 화면 로드에서 여러 API를 동시에 불러도
     * Nexon 매치 상세 호출은 매치당 딱 한 번만 나간다.
     */
    @GetMapping("/overall")
    public OverallRecordResponse getOverall(@RequestParam String nickname,
                                             @RequestParam MatchType matchType,
                                             @RequestParam(required = false) Integer limit) {
        return searchFacade.getOverall(nickname, matchType, limit);
    }

    /** "전체 선수 스탯" 그리드 — 전원(TOP N 아님), contributionScore 내림차순. */
    @GetMapping("/players")
    public List<TopPlayerResponse> getPlayers(@RequestParam String nickname,
                                               @RequestParam MatchType matchType,
                                               @RequestParam(required = false) Integer limit) {
        return searchFacade.getPlayers(nickname, matchType, limit);
    }

    /** "슈팅 위치 & 실제 xG값" — goalsOnly(선택, 기본 false)로 득점만 볼지 전체 슛을 볼지. */
    @GetMapping("/shot-heatmap")
    public ShotHeatmapResponse getShotHeatmap(@RequestParam String nickname,
                                               @RequestParam MatchType matchType,
                                               @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false, defaultValue = "false") boolean goalsOnly) {
        return searchFacade.getShotHeatmap(nickname, matchType, limit, goalsOnly);
    }

    /** "평균 실점 xG값"(수비 성향)용 — 상대가 나를 향해 쏜 슛 전체. */
    @GetMapping("/conceded-shot-heatmap")
    public ShotHeatmapResponse getConcededShotHeatmap(@RequestParam String nickname,
                                                        @RequestParam MatchType matchType,
                                                        @RequestParam(required = false) Integer limit) {
        return searchFacade.getConcededShotHeatmap(nickname, matchType, limit);
    }

    /** xA(기대 어시스트) 히트맵용 — 어시스트가 달린 슛(골 여부 무관)만. */
    @GetMapping("/assisted-shot-heatmap")
    public ShotHeatmapResponse getAssistedShotHeatmap(@RequestParam String nickname,
                                                        @RequestParam MatchType matchType,
                                                        @RequestParam(required = false) Integer limit) {
        return searchFacade.getAssistedShotHeatmap(nickname, matchType, limit);
    }

    /** "환상의 콤비" — 어시스트→득점 조합. chainLimit(선택, 기본 10, 최대 200)은 서버가 반환할
     * 조합 개수(프론트는 이 목록을 A↔B 양방향 합산해 TOP N만 보여준다, report.js와 동일). */
    @GetMapping("/assist-chains")
    public List<AssistChainResponse> getAssistChains(@RequestParam String nickname,
                                                       @RequestParam MatchType matchType,
                                                       @RequestParam(required = false) Integer limit,
                                                       @RequestParam(required = false) Integer chainLimit) {
        return searchFacade.getAssistChains(nickname, matchType, limit, chainLimit);
    }

    /** "최근 경기" 목록 · 플레이 성향/바이오리듬 추이 차트용. */
    @GetMapping("/recent-matches")
    public List<RecentMatchResponse> getRecentMatches(@RequestParam String nickname,
                                                        @RequestParam MatchType matchType,
                                                        @RequestParam(required = false) Integer limit) {
        return searchFacade.getRecentMatches(nickname, matchType, limit);
    }

    /** 매치 상세 모달의 득점/실점 상세 — 위 API들이 반환한 matchId를 그대로 넘긴다. */
    @GetMapping("/match-shots")
    public MatchShotsResponse getMatchShots(@RequestParam String ouid, @RequestParam String matchId) {
        return searchFacade.getMatchShots(ouid, matchId);
    }

    /** 매치 상세 모달의 MOM/Worst — ouid에 검색 대상 또는 그 상대 ouid 아무거나 넘길 수 있다. */
    @GetMapping("/match-squad")
    public List<MatchSquadEntryResponse> getMatchSquad(@RequestParam String ouid, @RequestParam String matchId) {
        return searchFacade.getMatchSquad(ouid, matchId);
    }

    /** 매치 상세 모달의 "⚖️ 상대 팀 비교" — 양쪽 팀 스탯을 한 번에 반환(상대도 항상 채워짐). */
    @GetMapping("/match-stats")
    public SearchMatchStatsResponse getMatchStats(@RequestParam String ouid, @RequestParam String matchId) {
        return searchFacade.getMatchStats(ouid, matchId);
    }
}
