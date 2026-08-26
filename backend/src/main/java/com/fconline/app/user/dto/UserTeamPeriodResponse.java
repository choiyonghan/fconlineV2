package com.fconline.app.user.dto;

import com.fconline.domain.user.UserTeamPeriod;
import java.time.LocalDate;

/** "사용한 팀" 필터 칩 UI용 — id는 이 기간을 다른 조회 API의 teamPeriodId 파라미터로 그대로 넘기는 값. */
public record UserTeamPeriodResponse(Long id, String teamName, LocalDate startDate, LocalDate endDate) {

    public static UserTeamPeriodResponse from(UserTeamPeriod period) {
        return new UserTeamPeriodResponse(period.getId(), period.getTeamName(), period.getStartDate(), period.getEndDate());
    }
}
