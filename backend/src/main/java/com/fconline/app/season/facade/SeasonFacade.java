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
     * 시즌 목록은 새 시즌이 나올 때만 바뀌는 참조 데이터라 Redis 캐시 대상(TTL 3시간,
     * RedisCacheConfig.TTL — 조회성 데이터는 전부 이 값으로 통일, 요청). SeasonResponse.current는
     * "오늘" 기준으로 계산되는 값이라 시즌 경계를 넘는 순간 최대 3시간까지는 오차가 있을 수
     * 있다는 점만 인지하고 있으면 된다.
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
