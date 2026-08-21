package com.fconline.application.user.dto;

import com.fconline.domain.user.TrackedUser;

public record TrackedUserResponse(String ouid, String nickname, int displayOrder) {

    public static TrackedUserResponse from(TrackedUser user) {
        return new TrackedUserResponse(user.getOuid(), user.getNickname(), user.getDisplayOrder());
    }
}
