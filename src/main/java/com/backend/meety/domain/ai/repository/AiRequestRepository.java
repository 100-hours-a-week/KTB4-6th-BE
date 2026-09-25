package com.backend.meety.domain.ai.repository;

import com.backend.meety.domain.ai.entity.AiRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiRequestRepository extends JpaRepository<AiRequest, Long> {

    Optional<AiRequest> findByIdempotencyKey(String idempotencyKey);
}
