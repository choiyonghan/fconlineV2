package com.fconline.app.common;

import com.fconline.domain.season.Season;
import com.fconline.domain.shared.KstZone;
import com.fconline.domain.shared.exception.DomainException;
import com.fconline.domain.user.UserTeamPeriod;
import com.fconline.domain.user.repository.UserTeamPeriodRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * "사용한 팀" 필터(teamPeriodId, 선택) — 매치타입/시즌 아래에 팀 버튼을 추가해달라는 요청.
 * user_team_periods 한 행(기간)을 골라 시즌 범위와 교집합 낸 실제 조회 범위를 계산한다.
 * 팀 이름이 같아도 기간(행)이 다르면 별개로 취급한다(합치지 않는다 — 사용자 확정 사항,
 * "맨유 -> 단일 -> 맨유"면 두 맨유 구간을 각각 독립된 필터 버튼으로 보여준다).
 * 여러 Facade(RecordFacade/OpponentFacade)가 공유하는 조회 전용 헬퍼 — SeasonRangeResolver와 짝.
 */
@Component
public class TeamPeriodRangeResolver {

    private final UserTeamPeriodRepository userTeamPeriodRepository;

    public TeamPeriodRangeResolver(UserTeamPeriodRepository userTeamPeriodRepository) {
        this.userTeamPeriodRepository = userTeamPeriodRepository;
    }

    public record EffectiveRange(Instant from, Instant to) {
    }

    /** season의 범위를 teamPeriodId(선택)로 좁힌다. teamPeriodId가 없으면 season 범위 그대로. */
    public EffectiveRange narrow(Season season, Long teamPeriodId) {
        Instant from = season.startInstant();
        Instant to = season.endInstantExclusiveOrNull();
        if (teamPeriodId == null) {
            return new EffectiveRange(from, to);
        }

        UserTeamPeriod period = userTeamPeriodRepository.findById(teamPeriodId)
                .orElseThrow(() -> new DomainException("존재하지 않는 팀 기간입니다: " + teamPeriodId));
        Instant periodFrom = period.getStartDate().atStartOfDay(KstZone.ID).toInstant();
        Instant periodToExclusive = period.getEndDate() == null ? null
                : period.getEndDate().plusDays(1).atStartOfDay(KstZone.ID).toInstant();

        return new EffectiveRange(laterOf(from, periodFrom), earlierOfNullable(to, periodToExclusive));
    }

    private static Instant laterOf(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    /** null = 상한 없음(진행 중) — 둘 중 하나만 null이면 나머지 쪽이 더 좁은 상한이다. */
    private static Instant earlierOfNullable(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }
}
