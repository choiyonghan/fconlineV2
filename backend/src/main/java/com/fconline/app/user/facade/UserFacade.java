package com.fconline.app.user.facade;

import com.fconline.app.user.dto.TrackedUserResponse;
import com.fconline.app.user.dto.UserTeamPeriodResponse;
import com.fconline.domain.user.repository.TrackedUserRepository;
import com.fconline.domain.user.repository.UserTeamPeriodRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UserFacade {

    private final TrackedUserRepository trackedUserRepository;
    private final UserTeamPeriodRepository userTeamPeriodRepository;

    public UserFacade(TrackedUserRepository trackedUserRepository, UserTeamPeriodRepository userTeamPeriodRepository) {
        this.trackedUserRepository = trackedUserRepository;
        this.userTeamPeriodRepository = userTeamPeriodRepository;
    }

    @Transactional(readOnly = true)
    public List<TrackedUserResponse> listTrackedUsers() {
        return trackedUserRepository.findByTrackedTrueOrderByDisplayOrderAsc().stream()
                .map(TrackedUserResponse::from)
                .toList();
    }

    /** "사용한 팀" 필터 칩 UI용 — 매치타입/시즌 아래에 이 유저의 팀 기간을 버튼으로 보여준다(요청). */
    @Transactional(readOnly = true)
    public List<UserTeamPeriodResponse> listTeamPeriods(String ouid) {
        return userTeamPeriodRepository.findByOuidOrderByStartDateAsc(ouid).stream()
                .map(UserTeamPeriodResponse::from)
                .toList();
    }
}
