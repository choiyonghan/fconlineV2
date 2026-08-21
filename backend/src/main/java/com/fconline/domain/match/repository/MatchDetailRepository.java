package com.fconline.domain.match.repository;

import com.fconline.domain.match.MatchDetail;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA 표준 CRUD는 이 인터페이스로, 복잡한 집계 쿼리는
 * {@link MatchDetailRepositoryCustom}(구현체는 infrastructure.persistence.match)으로 분리한다.
 */
public interface MatchDetailRepository extends JpaRepository<MatchDetail, Long>, MatchDetailRepositoryCustom {
}
