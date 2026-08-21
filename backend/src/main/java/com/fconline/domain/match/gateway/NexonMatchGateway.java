package com.fconline.domain.match.gateway;

import com.fconline.domain.match.vo.MatchType;
import java.util.List;
import java.util.Optional;

/**
 * 도메인이 정의하는 외부 의존성 포트. Nexon Open API에 대한 의존을 역전시켜
 * 도메인/응용 계층 테스트가 실제 HTTP 호출 없이 Mock으로 완전히 대체 가능하게 한다.
 * 구현체: infrastructure.nexon.NexonApiClient.
 */
public interface NexonMatchGateway {

    /** 닉네임 → ouid. 존재하지 않으면 empty. */
    Optional<String> findOuid(String nickname);

    /** 최근 매치 ID 목록 (최대 limit건, 최신순). */
    List<String> findRecentMatchIds(String ouid, MatchType matchType, int limit);

    /** 경기 상세 (양 참가자 데이터 포함). */
    NexonMatchData fetchMatchDetail(String matchId);
}
