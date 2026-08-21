package com.fconline.domain.season.repository;

import com.fconline.domain.season.Season;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeasonRepository extends JpaRepository<Season, Long> {

    List<Season> findAllByOrderByStartDateDesc();

    @Query("select s from Season s where :reference >= s.startDate and (s.endDate is null or :reference <= s.endDate)")
    Optional<Season> findCurrent(@Param("reference") LocalDate reference);
}
