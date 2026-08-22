package com.fconline.app.season.facade;

import com.fconline.app.season.dto.SeasonResponse;
import com.fconline.domain.shared.KstZone;
import com.fconline.domain.season.repository.SeasonRepository;
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
        LocalDate today = LocalDate.now(KstZone.ID);
        return seasonRepository.findAllByOrderByStartDateDesc().stream()
                .map(season -> SeasonResponse.from(season, today))
                .toList();
    }
}
