package com.fconline.app.user.facade;

import com.fconline.app.user.dto.TrackedUserResponse;
import com.fconline.app.user.dto.UserTeamPeriodResponse;
import com.fconline.domain.user.repository.TrackedUserRepository;
import com.fconline.domain.user.repository.UserTeamPeriodRepository;
import com.fconline.infrastructure.cache.CacheNames;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
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

    /**
     * report.html이 유저 칩을 처음 클릭할 때마다 부르는 API — 추적 유저 명단은 관리자가 수동으로
     * 추가/제외할 때만 바뀌는 참조 데이터라 Redis 캐시 대상(TTL 1시간, RedisCacheConfig)으로 삼는다.
     */
    @Cacheable(CacheNames.TRACKED_USERS)
    @Transactional(readOnly = true)
    public List<TrackedUserResponse> listTrackedUsers() {
        return trackedUserRepository.findByTrackedTrueOrderByDisplayOrderAsc().stream()
                .map(TrackedUserResponse::from)
                .toList();
    }

    /**
     * "사용한 팀" 필터 칩 UI용 — 매치타입/시즌 아래에 이 유저의 팀 기간을 버튼으로 보여준다(요청).
     * Redis 캐시 대상(TTL 5분) — 매치 동기화로 팀 기간이 갱신될 수 있어 records와 같은 TTL을 쓴다.
     */
    @Cacheable(CacheNames.TEAM_PERIODS)
    @Transactional(readOnly = true)
    public List<UserTeamPeriodResponse> listTeamPeriods(String ouid) {
        return userTeamPeriodRepository.findByOuidOrderByStartDateDesc(ouid).stream()
                .map(UserTeamPeriodResponse::from)
                .toList();
    }
}
