package com.fconline.app.opponent.facade;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.common.dto.MatchTallyResponse;
import com.fconline.app.common.dto.StreakBadgeResponse;
import com.fconline.app.opponent.dto.OpponentMatchResponse;
import com.fconline.app.opponent.dto.OpponentSummaryResponse;
import com.fconline.domain.match.MatchDetail;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.service.MatchDomainService;
import com.fconline.domain.match.vo.MatchTally;
import com.fconline.domain.match.vo.OpponentTally;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.score.service.ScoreCalculationService;
import com.fconline.domain.season.Season;
import com.fconline.domain.streak.OpponentStreak;
import com.fconline.domain.streak.repository.OpponentStreakRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상대별 카드 목록(화면 4) + 상대 상세 경기 목록(화면 5) 유스케이스.
 * matchType은 CUSTOM/OFFICIAL 공용 파라미터로 받되, 상대별 분해가 의미 없는 OFFICIAL은
 * 빈 목록을 반환한다 — v1처럼 별도 API/페이지를 만들지 않는다(analysis 6.13-6 해결).
 */
@Component
public class OpponentFacade {

    private final SeasonRangeResolver seasonRangeResolver;
    private final MatchDomainService matchDomainService;
    private final MatchDetailRepository matchDetailRepository;
    private final OpponentStreakRepository opponentStreakRepository;
    private final ScoreCalculationService scoreCalculationService;

    public OpponentFacade(SeasonRangeResolver seasonRangeResolver, MatchDomainService matchDomainService,
                           MatchDetailRepository matchDetailRepository,
                           OpponentStreakRepository opponentStreakRepository,
                           ScoreCalculationService scoreCalculationService) {
        this.seasonRangeResolver = seasonRangeResolver;
        this.matchDomainService = matchDomainService;
        this.matchDetailRepository = matchDetailRepository;
        this.opponentStreakRepository = opponentStreakRepository;
        this.scoreCalculationService = scoreCalculationService;
    }

    @Transactional(readOnly = true)
    public List<OpponentSummaryResponse> listOpponents(String ouid, MatchType matchType, Long seasonId) {
        if (matchType == MatchType.OFFICIAL) {
            return List.of();
        }

        Season season = seasonRangeResolver.resolve(seasonId);
        var from = season.startInstant();
        var to = season.endInstantExclusiveOrNull();

        List<OpponentTally> tallies = matchDomainService.opponentTallies(ouid, matchType, from, to);

        return tallies.stream()
                .map(tally -> toSummary(ouid, matchType, season.getId(), tally))
                .toList();
    }

    private OpponentSummaryResponse toSummary(String ouid, MatchType matchType, Long seasonId, OpponentTally tally) {
        StreakBadgeResponse streak = opponentStreakRepository
                .findByOuidAndOpponentOuidAndMatchTypeAndSeasonId(ouid, tally.opponentOuid(), matchType, seasonId)
                .map(StreakBadgeResponse::from)
                .orElseGet(StreakBadgeResponse::empty);

        MatchTally matchTally = new MatchTally(tally.wins(), tally.draws(), tally.losses(), 0, 0);
        int dugsikScore = scoreCalculationService.calculate(ouid, matchTally);

        return new OpponentSummaryResponse(
                tally.opponentOuid(),
                tally.opponentNickname(),
                MatchTallyResponse.from(matchTally),
                streak,
                dugsikScore
        );
    }

    @Transactional(readOnly = true)
    public Page<OpponentMatchResponse> listOpponentMatches(String ouid, String opponentOuid, MatchType matchType,
                                                            Long seasonId, Pageable pageable) {
        Season season = seasonRangeResolver.resolve(seasonId);

        Page<MatchDetail> page = matchDetailRepository.findByOuidAndOpponent(
                ouid, opponentOuid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), pageable);

        return page.map(this::toMatchResponse);
    }

    private OpponentMatchResponse toMatchResponse(MatchDetail detail) {
        var stats = detail.getStats();
        return new OpponentMatchResponse(
                detail.getMatch().getMatchId(),
                detail.getMatch().getMatchDate(),
                detail.getResult().label(),
                nz(stats.getGoalsFor()),
                nz(stats.getGoalsAgainst()),
                stats.getAverageRating(),
                stats.getPossession(),
                stats.getShootTotal(),
                stats.getEffectiveShoot(),
                stats.getPassTry(),
                stats.getPassSuccess(),
                stats.getTackleTry(),
                stats.getTackleSuccess(),
                stats.getFoul(),
                stats.getYellowCards(),
                stats.getRedCards()
        );
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
