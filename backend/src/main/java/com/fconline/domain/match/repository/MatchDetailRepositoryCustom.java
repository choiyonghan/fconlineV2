package com.fconline.domain.match.repository;

import com.fconline.domain.match.vo.AssistChainCount;
import com.fconline.domain.match.vo.GoalTimeRaw;
import com.fconline.domain.match.vo.GoalTypeCount;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchGoalEvent;
import com.fconline.domain.match.vo.MatchShotDetail;
import com.fconline.domain.match.vo.MatchSquadEntryRaw;
import com.fconline.domain.match.vo.MatchStatsSummary;
import com.fconline.domain.match.vo.PlayerShotPoint;
import com.fconline.domain.match.vo.MatchTally;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.OpponentTally;
import com.fconline.domain.match.vo.PlayerGrade;
import com.fconline.domain.match.vo.RecentMatchRaw;
import com.fconline.domain.match.vo.ShotPoint;
import com.fconline.domain.match.vo.TopPlayerStat;
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

    /**
     * 선수별(spId) 골/어시스트/수비 기여 집계, 기여도 점수 내림차순 상위 limit건.
     * opponentOuid가 null이면 전체 상대 합산, 지정하면 그 상대와의 경기만(상대별 전적 펼침에서
     * "이 상대전 최다 득점/도움/선방/수비 TOP3"를 보여줄 때 씀 — aggregateTally와 같은 패턴).
     */
    List<TopPlayerStat> aggregateTopPlayers(String ouid, MatchType matchType, Instant from, Instant to,
                                             String opponentOuid, int limit);

    /** 득점(ShootResult.GOAL)만 대상으로 슛 유형별 개수 집계. */
    List<GoalTypeCount> aggregateGoalTypeDistribution(String ouid, MatchType matchType, Instant from, Instant to);

    /**
     * 득점 시각(분,period) 원시값 목록 — period별 절대 분 환산 및 버킷 계산은
     * 도메인 서비스(MatchDomainService)가 담당한다. minute은 해당 period 시작 기준 경과분이라
     * period 오프셋을 더하기 전까지는 "경기 시작 기준 누적 분"이 아니다 — 예전엔 이 오프셋 없이
     * minute을 그대로 버킷팅해서 후반/연장 골이 전부 0~45분대로 잘못 집계되고 있었다.
     */
    List<GoalTimeRaw> findGoalMinutes(String ouid, MatchType matchType, Instant from, Instant to);

    /** 상대별 승/무/패 집계 목록 (상대별 카드 화면의 기반 데이터). */
    List<OpponentTally> aggregateOpponentTallies(String ouid, MatchType matchType, Instant from, Instant to);

    /**
     * 특정 상대와의 경기 결과를 매치 날짜 오름차순으로 반환한다 (스트릭 재계산 전용, 가벼운 프로젝션).
     * matchType은 필수 파라미터로 강제해 v1의 필터 누락 버그(analysis 6.2)가 재발하지 않게 한다.
     */
    List<MatchResult> findChronologicalResults(String ouid, String opponentOuid, MatchType matchType,
                                                Instant from, Instant to);

    /**
     * 특정 상대와의 개별 경기 목록 (페이지네이션). 목록 화면은 shoot_detail/player_squad/
     * raw_participant jsonb 원본을 쓰지 않으므로, MatchDetail 전체 엔티티가 아니라
     * 필요한 스칼라 컬럼만 뽑는 경량 프로젝션(RecentMatchRaw)으로 반환한다.
     */
    Page<RecentMatchRaw> findByOuidAndOpponent(String ouid, String opponentOuid, MatchType matchType,
                                                Instant from, Instant to, Pageable pageable);

    /**
     * 상대 무관, 이 유저의 전체 최근 경기 목록 — 매치 날짜 내림차순 (페이지네이션).
     * findByOuidAndOpponent와 마찬가지로 경량 프로젝션(RecentMatchRaw)을 쓴다.
     */
    Page<RecentMatchRaw> findRecentByOuid(String ouid, MatchType matchType, Instant from, Instant to, Pageable pageable);

    /**
     * 매치 상세 모달의 "상대 스탯 비교"용 — 특정 매치 1건, 특정 ouid 관점의 팀 스탯 한 행.
     * ouid가 그 매치의 참가자가 아니면(상대가 추적 대상이 아니거나 매치를 안 찾음) 빈 Optional.
     * matchType은 필수 파라미터로 강제(다른 findXxx와 같은 이유 — analysis 6.2 재발 방지).
     */
    java.util.Optional<RecentMatchRaw> findByOuidAndMatchId(String ouid, MatchType matchType, String matchId);

    /**
     * 좌표 히트맵용 슛 위치 원시 목록. goalsOnly=true면 득점한 슛만. 좌표가 없는 행은 제외.
     * opponentOuid가 null이면 전체 상대 합산, 지정하면 그 상대와의 경기만(상대별 전적 펼침의
     * "이 상대전 평균 득점 xG값" 계산용 — aggregateTally와 같은 패턴).
     */
    List<ShotPoint> findShotPoints(String ouid, MatchType matchType, Instant from, Instant to,
                                    String opponentOuid, boolean goalsOnly);

    /**
     * "실점 xG값"용 — 상대도 추적 대상 유저인 매치에 한해, 그 상대가 이 유저를 향해 쏜 슛 좌표
     * 목록(상대 본인 관점으로 동기화된 shootEvents를 그대로 가져온다). 상대가 추적 대상이 아니면
     * 그 매치는 결과에서 빠진다. opponentOuid가 null이면 전체 상대 합산, 지정하면 그 상대와의
     * 경기만(상대별 전적 펼침의 "이 상대전 평균 실점 xG값" 계산용).
     */
    List<ShotPoint> findConcededShotPoints(String ouid, MatchType matchType, Instant from, Instant to,
                                            String opponentOuid);

    /**
     * 특정 매치 1건의 슛 이벤트 전체(위치/유형/결과/득점 시각/어시스트 여부) — 매치 상세 모달에서
     * "누가 골, 누가 어시, 어디서 슛했는지"를 보여줄 때 쓴다. matchId+ouid+matchType으로 정확히
     * 한 참가자 시점의 슛 목록만 가져온다(같은 matchId라도 참가자마다 각자의 shoot_events 행이
     * 따로 있다).
     */
    List<MatchShotDetail> findShotsByMatch(String ouid, MatchType matchType, String matchId);

    /**
     * 매치 상세 모달의 "실점 상세"용 — 특정 매치 1건에서 상대가 이 유저를 향해 쏜 슛 이벤트
     * 전체. findConcededShotPoints와 같은 원리(상대도 추적 대상이어야 그 상대 본인 관점 행을
     * 찾을 수 있음)를 매치 1건으로 좁힌 버전이다. 상대가 추적 대상이 아니면 빈 목록.
     */
    List<MatchShotDetail> findConcededShotsByMatch(String ouid, MatchType matchType, String matchId);

    /** 어시스트→득점 선수 조합별 골 수, 내림차순 상위 limit건. */
    List<AssistChainCount> aggregateAssistChains(String ouid, MatchType matchType, Instant from, Instant to, int limit);

    /**
     * spId별 "가장 최근 매치에서 관측된" 카드 강화 단계(0~11강). shoot_events.sp_grade는 이미
     * 저장돼 있는 컬럼이라 새 컬럼/마이그레이션 없이 그대로 조회한다 — 슛을 한 번도 안 쏜
     * 선수(예: 무실점만 지킨 골키퍼)는 shoot_events에 행이 없어 결과에서 빠진다.
     */
    List<PlayerGrade> findLatestSpGrades(String ouid, MatchType matchType, Instant from, Instant to);

    /**
     * 특정 상대와의 매치들에서 나온 골 이벤트 전체(누구 골인지 + 시각) — "선제골" 분석용
     * (AI 인사이트 스냅샷). 상대 쪽 골은 findConcededShotPoints와 같은 원리(상대 본인 관점
     * shoot_events)로 가져온다 — 상대가 추적 대상이 아니면 상대 쪽 골은 빠진다.
     */
    List<MatchGoalEvent> findGoalEventsVsOpponent(String ouid, MatchType matchType, Instant from, Instant to,
                                                   String opponentOuid);

    /**
     * 매치 상세 모달의 MOM/Worst Player용 — 특정 매치 1건에서 이 ouid의 스쿼드 11(+교체)명 전체
     * (평점 포함). findShotsByMatch와 같은 원리로 matchId+ouid+matchType으로 정확히 한 참가자
     * 시점의 행만 가져온다.
     */
    List<MatchSquadEntryRaw> findSquadByMatch(String ouid, MatchType matchType, String matchId);

    /**
     * "전체 선수 스탯"의 선수별 xG 합산용 — spId가 붙은 슛 좌표 전체(득점 여부 무관, findShotPoints와
     * 같은 원리지만 spId를 추가로 선택). opponentOuid가 null이면 전체 상대 합산, 지정하면 그
     * 상대와의 경기만("상대별 전적" 펼침에서 "이 상대전 xG" 계산용 — 다른 findXxx와 같은 패턴).
     */
    List<PlayerShotPoint> findShotPointsByPlayer(String ouid, MatchType matchType, Instant from, Instant to,
                                                  String opponentOuid);
}
