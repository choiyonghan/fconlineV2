package com.fconline.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추적 대상 유저 애그리게잇. v1에서 4곳에 하드코딩되어 있던 닉네임 목록
 * (fetch_and_store.js, fetch_official.js, build-data.js, official.js)을 이 테이블 하나로 대체한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "tracked_users")
public class TrackedUser {

    @Id
    @Column(name = "ouid", nullable = false, updatable = false, length = 64)
    private String ouid;

    @Column(name = "nickname", nullable = false, unique = true)
    private String nickname;

    @Column(name = "is_tracked", nullable = false)
    private boolean tracked;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static TrackedUser register(String ouid, String nickname, int displayOrder) {
        TrackedUser user = new TrackedUser();
        user.ouid = ouid;
        user.nickname = nickname;
        user.tracked = true;
        user.displayOrder = displayOrder;
        user.updatedAt = Instant.now();
        return user;
    }

    public void rename(String nickname) {
        this.nickname = nickname;
        this.updatedAt = Instant.now();
    }

    public void stopTracking() {
        this.tracked = false;
        this.updatedAt = Instant.now();
    }
}
