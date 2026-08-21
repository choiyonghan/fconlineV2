package com.fconline.presentation.visitor;

import com.fconline.application.visitor.VisitorFacade;
import com.fconline.application.visitor.dto.VisitorSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/visitors")
public class VisitorController {

    private final VisitorFacade visitorFacade;

    public VisitorController(VisitorFacade visitorFacade) {
        this.visitorFacade = visitorFacade;
    }

    @GetMapping("/summary")
    public VisitorSummaryResponse summary() {
        return visitorFacade.summary();
    }

    @PostMapping("/visits")
    public VisitorSummaryResponse recordVisit() {
        return visitorFacade.recordVisit();
    }
}
