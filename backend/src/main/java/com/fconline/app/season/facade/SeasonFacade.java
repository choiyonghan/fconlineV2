package com.fconline.app.season.facade;

import com.fconline.app.season.dto.SeasonResponse;
import com.fconline.domain.shared.KstZone;
import com.fconline.domain.season.repository.SeasonRepository;
import com.fconline.infrastructure.cache.CacheNames;
import java.time.LocalDate;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SeasonFacade {

    private final SeasonRepository seasonRepository;

    public SeasonFacade(SeasonRepository seasonRepository) {
        this.seasonRepository = seasonRepository;
    }

    /**
     * 시즌 목록은 새 시즌이 나올 때만 바뀌는 참조 데이터라 Redis 캐시 대상(TTL 1시간,
     * RedisCacheConfig)이지만, SeasonResponse.current는 "오늘" 기준으로 계산되는 값이라
     * records(5분)보단 길고 하루보단 짧게(1시간) 잡아서 시즌 경계를 넘는 순간의 오차를 줄인다.
     */
    @Cacheable(CacheNames.SEASONS)
    @Transactional(readOnly = true)
    public List<SeasonResponse> listSeasons() {
        LocalDate today = LocalDate.now(KstZone.ID);
        return seasonRepository.findAllByOrderByStartDateDesc().stream()
                .map(season -> SeasonResponse.from(season, today))
                .toList();
    }
}
