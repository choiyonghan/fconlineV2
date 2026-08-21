package com.fconline.application.season;

import com.fconline.application.season.dto.SeasonResponse;
import com.fconline.domain.season.SeasonRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SeasonFacade {

    private final SeasonRepository seasonRepository;

    public SeasonFacade(SeasonRepository seasonRepository) {
        this.seasonRepository = seasonRepository;
    }

    @Transactional(readOnly = true)
    public List<SeasonResponse> listSeasons() {
        LocalDate today = LocalDate.now();
        return seasonRepository.findAllByOrderByStartDateDesc().stream()
                .map(season -> SeasonResponse.from(season, today))
                .toList();
    }
}
