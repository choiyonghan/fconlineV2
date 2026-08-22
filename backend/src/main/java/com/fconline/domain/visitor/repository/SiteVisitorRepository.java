package com.fconline.domain.visitor.repository;

import com.fconline.domain.visitor.SiteVisitor;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SiteVisitorRepository extends JpaRepository<SiteVisitor, LocalDate> {

    Optional<SiteVisitor> findByVisitDate(LocalDate visitDate);

    @Query("select coalesce(sum(v.count), 0) from SiteVisitor v")
    long sumAllCounts();

    /**
     * find-then-insert/increment 대신 원자적 UPSERT. 방문 기록은 페이지가 로드될 때마다
     * 동시에 여러 요청으로 들어올 수 있는데, findByVisitDate 이후 save()로는 같은 날 "첫
     * 방문"을 여러 요청이 동시에 보면 site_visitors_pkey unique 제약 위반으로 죽었다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO v2.site_visitors (visit_date, count)
            VALUES (:visitDate, 1)
            ON CONFLICT (visit_date) DO UPDATE
                SET count = v2.site_visitors.count + 1
            """, nativeQuery = true)
    void incrementVisit(@Param("visitDate") LocalDate visitDate);
}
