package com.fconline.domain.visitor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "site_visitors")
public class SiteVisitor {

    @Id
    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "count", nullable = false)
    private long count;

    public static SiteVisitor firstVisitOf(LocalDate visitDate) {
        SiteVisitor visitor = new SiteVisitor();
        visitor.visitDate = visitDate;
        visitor.count = 1;
        return visitor;
    }

    public void increment() {
        this.count++;
    }
}
