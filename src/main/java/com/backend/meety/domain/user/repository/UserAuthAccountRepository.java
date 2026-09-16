package com.backend.meety.domain.user.repository;

import com.backend.meety.domain.user.entity.UserAuthAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuthAccountRepository extends JpaRepository<UserAuthAccount, Long> {

    Optional<UserAuthAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    List<UserAuthAccount> findAllByUserId(Long userId);
}
