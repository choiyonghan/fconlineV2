package com.fconline.app.insight.dto;

import com.fconline.domain.match.vo.MatchType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AskRequest(
        @NotBlank String ouid,
        @NotNull MatchType matchType,
        Long seasonId,
        @NotBlank @Size(max = 500) String question
) {
}
