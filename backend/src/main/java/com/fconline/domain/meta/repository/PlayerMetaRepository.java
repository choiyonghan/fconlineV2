package com.fconline.domain.meta.repository;

import com.fconline.domain.meta.PlayerMeta;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PlayerMetaRepository extends JpaRepository<PlayerMeta, String> {

    List<PlayerMeta> findBySpIdIn(Collection<String> spIds);

    /**
     * find-then-insert/update 대신 원자적 UPSERT. sync.yml의 matrix(CUSTOM/OFFICIAL) 두 job이
     * 각자 독립적으로 spid.json 전체를 동기화하다 보니, 두 job이 겹쳐 도는 구간에서
     * "동시에 같은 신규 spId를 처음 봄" 레이스가 나면 findById 이후 save()가
     * unique constraint 위반으로 죽었다 — INSERT ... ON CONFLICT로 그 레이스 자체를 제거한다.
     * 이름이 실제로 바뀐 경우에만 UPDATE되도록 WHERE로 걸러 반환값으로 갱신 여부를 알 수 있다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO v2.player_meta (sp_id, sp_name, updated_at)
            VALUES (:spId, :spName, now())
            ON CONFLICT (sp_id) DO UPDATE
                SET sp_name = excluded.sp_name, updated_at = excluded.updated_at
                WHERE v2.player_meta.sp_name IS DISTINCT FROM excluded.sp_name
            """, nativeQuery = true)
    int upsert(@Param("spId") String spId, @Param("spName") String spName);
}
