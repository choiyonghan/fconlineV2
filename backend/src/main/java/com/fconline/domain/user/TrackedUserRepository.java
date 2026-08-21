package com.fconline.domain.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 단순 CRUD만 필요한 애그리게잇이므로 포트/어댑터를 분리하지 않고
 * Spring Data JPA 인터페이스를 domain 패키지에 직접 둔다.
 */
public interface TrackedUserRepository extends JpaRepository<TrackedUser, String> {

    List<TrackedUser> findByTrackedTrueOrderByDisplayOrderAsc();

    Optional<TrackedUser> findByNickname(String nickname);
}
