package com.fconline.application.season.dto;

import com.fconline.domain.season.Season;
import java.time.LocalDate;

public record SeasonResponse(Long id, String name, LocalDate startDate, LocalDate endDate, boolean current) {

    public static SeasonResponse from(Season season, LocalDate reference) {
        return new SeasonResponse(season.getId(), season.getName(), season.getStartDate(),
                season.getEndDate(), season.isCurrent(reference));
    }
}
