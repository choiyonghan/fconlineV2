package com.fconline.domain.user.repository;

import com.fconline.domain.user.UserTeamPeriod;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTeamPeriodRepository extends JpaRepository<UserTeamPeriod, Long> {

    /**
     * 여러 유저(대개 추적 대상 9명 전원)의 기간을 한 번에 읽어와 메모리에서 날짜 매칭한다 —
     * 매치 목록 한 페이지(최대 수백 행)마다 DB를 다시 치지 않기 위함. start_date 오름차순으로
     * 받아둬야 혹시 데이터 입력 실수로 기간이 겹치더라도 항상 더 이른(원래) 쪽이 먼저 매칭된다.
     */
    List<UserTeamPeriod> findByOuidInOrderByStartDateAsc(Collection<String> ouids);

    /** "사용한 팀" 필터 칩 UI용 — 이 유저의 기간 목록을 시간순으로. */
    List<UserTeamPeriod> findByOuidOrderByStartDateAsc(String ouid);
}
