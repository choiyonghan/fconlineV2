package com.fconline.application.common;

import com.fconline.domain.season.Season;
import com.fconline.domain.season.SeasonRepository;
import com.fconline.domain.shared.exception.DomainException;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * seasonId(선택)를 실제 Season 엔티티로 해석한다. null이면 오늘 기준 진행 중인 시즌을 사용한다.
 * 여러 Facade(RecordFacade/OpponentFacade)가 공유하는 조회 전용 헬퍼.
 */
@Component
public class SeasonRangeResolver {

    private final SeasonRepository seasonRepository;

    public SeasonRangeResolver(SeasonRepository seasonRepository) {
        this.seasonRepository = seasonRepository;
    }

    public Season resolve(Long seasonId) {
        if (seasonId != null) {
            return seasonRepository.findById(seasonId)
                    .orElseThrow(() -> new DomainException("존재하지 않는 시즌입니다: " + seasonId));
        }
        return seasonRepository.findCurrent(LocalDate.now())
                .orElseThrow(() -> new DomainException("진행 중인 시즌이 설정되어 있지 않습니다."));
    }
}
