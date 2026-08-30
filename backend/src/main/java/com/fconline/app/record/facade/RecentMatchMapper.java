package com.fconline.app.record.facade;

import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.domain.match.vo.RecentMatchRaw;
import com.fconline.domain.shared.KstZone;
import com.fconline.domain.user.UserTeamPeriod;
import com.fconline.domain.user.repository.UserTeamPeriodRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * RecentMatchRaw → RecentMatchResponse 매핑(사용한 팀명 붙이기 포함)을 RecordFacade와
 * RecentMatchesPageCache가 공유해서 쓰기 위한 별도 빈. RecentMatchesPageCache가 캐시 대상
 * DB 조회(원시 매치 목록 + 팀 기간 조회)를 전부 여기서 하도록 옮기면서, RecordFacade 안에
 * private 메서드로만 있던 이 로직을 순환 의존 없이 재사용하려고 분리했다.
 */
@Component
public class RecentMatchMapper {

    private final UserTeamPeriodRepository userTeamPeriodRepository;

    public RecentMatchMapper(UserTeamPeriodRepository userTeamPeriodRepository) {
        this.userTeamPeriodRepository = userTeamPeriodRepository;
    }

    /** user_team_periods를 한 번에 읽어 ouid별로 묶는다 — 목록 한 페이지마다 DB를 다시 안 친다. */
    public Map<String, List<UserTeamPeriod>> teamPeriodsByOuid(Set<String> ouids) {
        return userTeamPeriodRepository.findByOuidInOrderByStartDateAsc(ouids).stream()
                .collect(Collectors.groupingBy(UserTeamPeriod::getOuid));
    }

    public RecentMatchResponse toRecentMatchResponse(String ouid, RecentMatchRaw raw,
                                                       Map<String, List<UserTeamPeriod>> periodsByOuid) {
        return new RecentMatchResponse(
                raw.matchId(),
                raw.matchDate(),
                raw.opponentNickname(),
                raw.opponentOuid(),
                raw.result().label(),
                nz(raw.goalsFor()),
                nz(raw.goalsAgainst()),
                raw.averageRating(),
                raw.possession(),
                raw.shootTotal(),
                raw.effectiveShoot(),
                raw.passTry(),
                raw.passSuccess(),
                raw.tackleTry(),
                raw.tackleSuccess(),
                raw.foul(),
                raw.yellowCards(),
                raw.redCards(),
                resolveTeamAt(periodsByOuid, ouid, raw.matchDate()),
                resolveTeamAt(periodsByOuid, raw.opponentOuid(), raw.matchDate())
        );
    }

    /** matchDate 시점에 이 ouid가 쓰던 팀명 — 해당 기간 데이터가 없으면 null. */
    private String resolveTeamAt(Map<String, List<UserTeamPeriod>> periodsByOuid, String ouid, Instant matchDate) {
        if (ouid == null || matchDate == null) return null;
        List<UserTeamPeriod> periods = periodsByOuid.get(ouid);
        if (periods == null) return null;
        LocalDate date = matchDate.atZone(KstZone.ID).toLocalDate();
        for (UserTeamPeriod p : periods) {
            if (p.covers(date)) return p.getTeamName();
        }
        return null;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
