package com.fconline.app.user.controller;

import com.fconline.app.user.facade.UserFacade;
import com.fconline.app.user.dto.TrackedUserResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
