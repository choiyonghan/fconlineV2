package com.fconline.application.user;

import com.fconline.application.user.dto.TrackedUserResponse;
import com.fconline.domain.user.TrackedUserRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UserFacade {

    private final TrackedUserRepository trackedUserRepository;

    public UserFacade(TrackedUserRepository trackedUserRepository) {
        this.trackedUserRepository = trackedUserRepository;
    }

    @Transactional(readOnly = true)
    public List<TrackedUserResponse> listTrackedUsers() {
        return trackedUserRepository.findByTrackedTrueOrderByDisplayOrderAsc().stream()
                .map(TrackedUserResponse::from)
                .toList();
    }
}
