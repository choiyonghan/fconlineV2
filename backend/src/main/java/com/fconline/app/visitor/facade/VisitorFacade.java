package com.fconline.app.visitor.facade;

import com.fconline.app.visitor.dto.VisitorSummaryResponse;
import com.fconline.domain.visitor.SiteVisitor;
import com.fconline.domain.visitor.repository.SiteVisitorRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * v1의 site_visitors 테이블 + increment_visitor_count RPC를 대체.
 */
@Component
public class VisitorFacade {

    private final SiteVisitorRepository siteVisitorRepository;

    public VisitorFacade(SiteVisitorRepository siteVisitorRepository) {
        this.siteVisitorRepository = siteVisitorRepository;
    }

    @Transactional(readOnly = true)
    public VisitorSummaryResponse summary() {
        long today = siteVisitorRepository.findByVisitDate(LocalDate.now())
                .map(SiteVisitor::getCount)
                .orElse(0L);
        long total = siteVisitorRepository.sumAllCounts();
        return new VisitorSummaryResponse(today, total);
    }

    @Transactional
    public VisitorSummaryResponse recordVisit() {
        LocalDate today = LocalDate.now();
        SiteVisitor visitor = siteVisitorRepository.findByVisitDate(today).orElse(null);

        if (visitor == null) {
            siteVisitorRepository.save(SiteVisitor.firstVisitOf(today));
        } else {
            visitor.increment();
            siteVisitorRepository.save(visitor);
        }

        return summary();
    }
}
