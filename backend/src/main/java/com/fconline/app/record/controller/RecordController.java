package com.fconline.app.record.controller;

import com.fconline.app.record.facade.RecordFacade;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.domain.match.vo.MatchType;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/records")
public class RecordController {

    private final RecordFacade recordFacade;

    public RecordController(RecordFacade recordFacade) {
        this.recordFacade = recordFacade;
    }

    @GetMapping("/overall")
    public OverallRecordResponse getOverall(@RequestParam String ouid,
                                             @RequestParam MatchType matchType,
                                             @RequestParam(required = false) Long seasonId) {
        return recordFacade.getOverallRecord(ouid, matchType, seasonId);
    }

    /** 좌표 히트맵. goalsOnly=true면 득점한 슛만(기본값 false, 전체 슛). */
    @GetMapping("/shot-heatmap")
    public ShotHeatmapResponse getShotHeatmap(@RequestParam String ouid,
                                               @RequestParam MatchType matchType,
                                               @RequestParam(required = false) Long seasonId,
                                               @RequestParam(required = false, defaultValue = "false") boolean goalsOnly) {
        return recordFacade.getShotHeatmap(ouid, matchType, seasonId, goalsOnly);
    }

    /** 어시스트 체인 상위 목록(누가 누구에게 어시스트해서 득점했는지). */
    @GetMapping("/assist-chains")
    public List<AssistChainResponse> getAssistChains(@RequestParam String ouid,
                                                       @RequestParam MatchType matchType,
                                                       @RequestParam(required = false) Long seasonId) {
        return recordFacade.getAssistChains(ouid, matchType, seasonId);
    }

    /** 정렬 가능한 "전체 선수" 그리드, 최다 세이브 등 top-3 밖 통계용 — 사실상 전체 목록. */
    @GetMapping("/players")
    public List<TopPlayerResponse> getAllPlayers(@RequestParam String ouid,
                                                  @RequestParam MatchType matchType,
                                                  @RequestParam(required = false) Long seasonId) {
        return recordFacade.getAllPlayers(ouid, matchType, seasonId);
    }

    /** 상대 무관, 이 유저의 진짜 최신 경기 목록(매치 날짜 내림차순, 더보기 페이징). */
    @GetMapping("/recent-matches")
    public Page<RecentMatchResponse> getRecentMatches(@RequestParam String ouid,
                                                        @RequestParam MatchType matchType,
                                                        @RequestParam(required = false) Long seasonId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return recordFacade.getRecentMatches(ouid, matchType, seasonId, pageable);
    }
}
