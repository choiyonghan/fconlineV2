package com.fconline.presentation.season;

import com.fconline.application.season.SeasonFacade;
import com.fconline.application.season.dto.SeasonResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seasons")
public class SeasonController {

    private final SeasonFacade seasonFacade;

    public SeasonController(SeasonFacade seasonFacade) {
        this.seasonFacade = seasonFacade;
    }

    @GetMapping
    public List<SeasonResponse> listSeasons() {
        return seasonFacade.listSeasons();
    }
}
