package com.fconline.domain.user.repository;

import com.fconline.domain.user.UserTeamPeriod;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTeamPeriodRepository extends JpaRepository<UserTeamPeriod, Long> {

    /** 여러 유저(대개 추적 대상 9명 전원)의 기간을 한 번에 읽어와 메모리에서 날짜 매칭한다 —
     * 매치 목록 한 페이지(최대 수백 행)마다 DB를 다시 치지 않기 위함. */
    List<UserTeamPeriod> findByOuidIn(Collection<String> ouids);
}
