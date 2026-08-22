package com.fconline.domain.match.service;

import com.fconline.domain.match.Match;
import com.fconline.domain.match.MatchDetail;
import com.fconline.domain.match.ShootEvent;
import com.fconline.domain.match.SquadEntry;
import com.fconline.domain.match.gateway.NexonMatchData;
import com.fconline.domain.match.gateway.NexonParticipantData;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.repository.MatchRepository;
import com.fconline.domain.match.vo.MatchStats;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nexon 게이트웨이가 반환한 원시 데이터를 Match/MatchDetail/ShootEvent/SquadEntry로 매핑해 저장한다.
 * v1의 detailPayload 매핑(fetch_and_store.js:353-387, fetch_official.js:285-314)이
 * 두 스크립트에 거의 동일하게 중복돼 있던 것을 이 클래스 하나로 대체한다.
 */
@Service
public class MatchIngestionService {

    private final MatchRepository matchRepository;
    private final MatchDetailRepository matchDetailRepository;

    public MatchIngestionService(MatchRepository matchRepository, MatchDetailRepository matchDetailRepository) {
        this.matchRepository = matchRepository;
        this.matchDetailRepository = matchDetailRepository;
    }

    /** 이미 저장된 경기면 아무 것도 하지 않는다 (idempotent — 동기화 재실행 안전). */
    @Transactional
    public void ingest(NexonMatchData data) {
        if (matchRepository.existsById(data.matchId())) {
            return;
        }

        Match match = Match.of(data.matchId(), data.matchDate(), data.matchType());
        matchRepository.save(match);

        for (NexonParticipantData participant : data.participants()) {
            matchDetailRepository.save(toMatchDetail(match, participant));
        }
    }

    private MatchDetail toMatchDetail(Match match, NexonParticipantData participant) {
        MatchStats stats = MatchStats.builder()
                .controller(participant.controller())
                .averageRating(participant.averageRating())
                .goalsFor(participant.goalsFor())
                .goalsAgainst(participant.goalsAgainst())
                .shootTotal(participant.shootTotal())
                .effectiveShoot(participant.effectiveShoot())
                .goalInPenalty(participant.goalInPenalty())
                .goalOutPenalty(participant.goalOutPenalty())
                .shootHeading(participant.shootHeading())
                .ownGoal(participant.ownGoal())
                .possession(participant.possession())
                .passTry(participant.passTry())
                .passSuccess(participant.passSuccess())
                .shortPassTry(participant.shortPassTry())
                .throughPassTry(participant.throughPassTry())
                .throughPassSuccess(participant.throughPassSuccess())
                .tackleTry(participant.tackleTry())
                .tackleSuccess(participant.tackleSuccess())
                .foul(participant.foul())
                .yellowCards(participant.yellowCards())
                .redCards(participant.redCards())
                .offside(participant.offside())
                .build();

        MatchDetail detail = MatchDetail.of(match, participant.ouid(), participant.opponentOuid(),
                participant.opponentNickname(), participant.result(), stats,
                participant.shootDetailRaw(), participant.playerSquadRaw());

        participant.shootEvents().forEach(event -> detail.addShootEvent(
                ShootEvent.of(detail, event.shootType(), event.result(), event.goalTimeMinutes(), event.period(),
                        event.spId(), event.spGrade(), event.spLevel(), event.loaned(), event.x(), event.y(),
                        event.assist(), event.assistSpId(), event.assistX(), event.assistY(), event.hitPost(),
                        event.inPenalty())));

        participant.squadEntries().forEach(entry -> detail.addSquadEntry(
                SquadEntry.of(detail, entry.spId(), entry.spPosition(), entry.goal(), entry.assist(),
                        entry.save(), entry.tackle(), entry.intercept(), entry.block())));

        return detail;
    }
}
