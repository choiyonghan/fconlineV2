package com.fconline.app.record.controller;

import com.fconline.app.record.facade.RecordFacade;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.domain.match.vo.MatchType;
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
}
