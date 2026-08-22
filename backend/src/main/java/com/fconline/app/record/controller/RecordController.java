package com.fconline.app.record.controller;

import com.fconline.app.record.facade.RecordFacade;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.domain.match.vo.MatchType;
import java.util.List;
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
}
