package com.fconline.domain.visitor;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SiteVisitorRepository extends JpaRepository<SiteVisitor, LocalDate> {

    Optional<SiteVisitor> findByVisitDate(LocalDate visitDate);

    @Query("select coalesce(sum(v.count), 0) from SiteVisitor v")
    long sumAllCounts();
}
