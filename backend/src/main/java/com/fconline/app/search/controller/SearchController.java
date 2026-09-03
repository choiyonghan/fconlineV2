package com.fconline.app.search.controller;

import com.fconline.app.record.dto.MatchShotsResponse;
import com.fconline.app.record.dto.MatchSquadEntryResponse;
import com.fconline.app.search.dto.SearchMatchStatsResponse;
import com.fconline.app.search.dto.SearchResultResponse;
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
 */
@RestController
@RequestMapping("/api/v1/search/players")
public class SearchController {

    private final SearchFacade searchFacade;

    public SearchController(SearchFacade searchFacade) {
        this.searchFacade = searchFacade;
    }

    /**
     * 닉네임으로 검색 — 최근 matchType 경기 기준 요약/선수 기여도/최근 경기 목록. limit(선택,
     * 기본 15, 최대 30)은 몇 경기까지 조회할지. Nexon을 매치당 1번씩 호출하므로 limit이 크면
     * 응답이 그만큼 오래 걸린다(SearchFacade 클래스 주석 참고).
     */
    @GetMapping
    public SearchResultResponse search(@RequestParam String nickname,
                                        @RequestParam MatchType matchType,
                                        @RequestParam(required = false) Integer limit) {
        return searchFacade.search(nickname, matchType, limit);
    }

    /** 매치 상세 모달의 득점/실점 상세 — search() 결과의 matchId를 그대로 넘긴다. */
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
