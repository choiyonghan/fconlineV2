package com.fconline.domain.meta.repository;

import com.fconline.domain.meta.PlayerMeta;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerMetaRepository extends JpaRepository<PlayerMeta, String> {

    List<PlayerMeta> findBySpIdIn(Collection<String> spIds);
}
