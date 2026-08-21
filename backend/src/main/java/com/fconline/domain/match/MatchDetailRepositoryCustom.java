package com.fconline.domain.match;

import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 도메인이 정의하는 조회 포트. matchType은 모든 메서드에서 필수 파라미터로 강제해
 * v1의 "스트릭 집계 시 match_type 필터 누락" 버그(analysis 6.2)가 타입 레벨에서 재발하지 않게 한다.
 * 구현체(QueryDSL)는 infrastructure.persistence.match.MatchDetailRepositoryImpl.
 */
public interface MatchDetailRepositoryCustom {

    /** 지정 유저·매치타입·기간의 전체 승/무/패 + 득실 집계. opponentOuid가 null이면 전체 상대 합산. */
    MatchTally aggregateTally(String ouid, MatchType matchType, Instant from, Instant to, String opponentOuid);

    /** 평균 평점/점유율, 파울·카드 누계. */
    MatchStatsSummary aggregateStatsSummary(String ouid, MatchType matchType, Instant from, Instant to);

    /** 선수별(spId) 골/어시스트/수비 기여 집계, 기여도 점수 내림차순 상위 limit건. */
    List<TopPlayerStat> aggregateTopPlayers(String ouid, MatchType matchType, Instant from, Instant to, int limit);

    /** 득점(ShootResult.GOAL)만 대상으로 슛 유형별 개수 집계. */
    List<GoalTypeCount> aggregateGoalTypeDistribution(String ouid, MatchType matchType, Instant from, Instant to);

    /** 득점 시각(분) 원시값 목록 — 시간대 버킷 계산은 도메인 서비스(MatchDomainService)가 담당. */
    List<Integer> findGoalMinutes(String ouid, MatchType matchType, Instant from, Instant to);

    /** 상대별 승/무/패 집계 목록 (상대별 카드 화면의 기반 데이터). */
    List<OpponentTally> aggregateOpponentTallies(String ouid, MatchType matchType, Instant from, Instant to);

    /**
     * 특정 상대와의 경기 결과를 매치 날짜 오름차순으로 반환한다 (스트릭 재계산 전용, 가벼운 프로젝션).
     * matchType은 필수 파라미터로 강제해 v1의 필터 누락 버그(analysis 6.2)가 재발하지 않게 한다.
     */
    List<MatchResult> findChronologicalResults(String ouid, String opponentOuid, MatchType matchType,
                                                Instant from, Instant to);

    /** 특정 상대와의 개별 경기 목록 (페이지네이션). */
    Page<MatchDetail> findByOuidAndOpponent(String ouid, String opponentOuid, MatchType matchType,
                                             Instant from, Instant to, Pageable pageable);
}
