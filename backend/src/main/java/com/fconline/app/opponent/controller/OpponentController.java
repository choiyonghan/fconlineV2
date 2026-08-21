package com.fconline.app.opponent.controller;

import com.fconline.app.opponent.facade.OpponentFacade;
import com.fconline.app.opponent.dto.OpponentMatchResponse;
import com.fconline.app.opponent.dto.OpponentSummaryResponse;
import com.fconline.domain.match.vo.MatchType;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/opponents")
public class OpponentController {

    private final OpponentFacade opponentFacade;

    public OpponentController(OpponentFacade opponentFacade) {
        this.opponentFacade = opponentFacade;
    }

    @GetMapping
    public List<OpponentSummaryResponse> listOpponents(@RequestParam String ouid,
                                                         @RequestParam MatchType matchType,
                                                         @RequestParam(required = false) Long seasonId) {
        return opponentFacade.listOpponents(ouid, matchType, seasonId);
    }

    @GetMapping("/{opponentOuid}/matches")
    public Page<OpponentMatchResponse> listOpponentMatches(@PathVariable String opponentOuid,
                                                            @RequestParam String ouid,
                                                            @RequestParam MatchType matchType,
                                                            @RequestParam(required = false) Long seasonId,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return opponentFacade.listOpponentMatches(ouid, opponentOuid, matchType, seasonId, pageable);
    }
}
