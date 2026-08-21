package com.fconline.domain.match;

import java.util.Collection;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchRepository extends JpaRepository<Match, String> {

    /**
     * 동기화 시 "이미 수집된 경기인지"를 배치로 확인한다.
     * v1은 매치 ID 하나마다 개별 SELECT를 날려 실행당 최대 900회 왕복했다(analysis 6.10) —
     * 이 메서드 하나로 IN절 1회 조회로 대체한다.
     */
    @Query("select m.matchId from Match m where m.matchId in :matchIds")
    Set<String> findExistingMatchIds(@Param("matchIds") Collection<String> matchIds);
}
