package com.fconline.domain.meta;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Nexon 정적 메타(spid.json: 선수ID → 이름)의 서버측 캐시.
 * v1은 이 대용량 정적 파일을 페이지 로드마다 브라우저가 재요청했다(analysis 6.10) —
 * v2는 배치가 주기적으로 갱신하고, API 응답에 이름을 이미 포함해서 내려준다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "player_meta")
public class PlayerMeta {

    @Id
    @Column(name = "sp_id", nullable = false, updatable = false, length = 32)
    private String spId;

    @Column(name = "sp_name", nullable = false)
    private String spName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PlayerMeta of(String spId, String spName) {
        PlayerMeta meta = new PlayerMeta();
        meta.spId = spId;
        meta.spName = spName;
        meta.updatedAt = Instant.now();
        return meta;
    }

    public void rename(String spName) {
        this.spName = spName;
        this.updatedAt = Instant.now();
    }
}
