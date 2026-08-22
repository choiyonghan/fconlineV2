package com.fconline.app.insight.controller;

import com.fconline.app.insight.dto.AskRequest;
import com.fconline.app.insight.dto.AskResponse;
import com.fconline.app.insight.facade.InsightFacade;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
public class InsightController {

    private final InsightFacade insightFacade;

    public InsightController(InsightFacade insightFacade) {
        this.insightFacade = insightFacade;
    }

    /** 자연어 질문 + 현재 조회 컨텍스트(유저/매치타입/시즌)를 받아 Gemini가 분석한 답변을 반환한다. */
    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return insightFacade.ask(request);
    }
}
