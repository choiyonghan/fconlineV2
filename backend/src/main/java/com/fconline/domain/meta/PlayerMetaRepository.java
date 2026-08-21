package com.fconline.domain.meta;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerMetaRepository extends JpaRepository<PlayerMeta, String> {

    List<PlayerMeta> findBySpIdIn(Collection<String> spIds);
}
