package com.fconline.app.user.controller;

import com.fconline.app.user.facade.UserFacade;
import com.fconline.app.user.dto.TrackedUserResponse;
import com.fconline.app.user.dto.UserTeamPeriodResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserFacade userFacade;

    public UserController(UserFacade userFacade) {
        this.userFacade = userFacade;
    }

    @GetMapping
    public List<TrackedUserResponse> listUsers() {
        return userFacade.listTrackedUsers();
    }

    /** "사용한 팀" 필터 칩 UI용 — 이 유저의 팀 기간 목록(시간순). */
    @GetMapping("/team-periods")
    public List<UserTeamPeriodResponse> listTeamPeriods(@RequestParam String ouid) {
        return userFacade.listTeamPeriods(ouid);
    }
}
