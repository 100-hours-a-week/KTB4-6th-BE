package com.backend.meety.domain.ai.repository;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiRequestRepository extends JpaRepository<AiRequest, Long> {

    Optional<AiRequest> findByIdempotencyKey(String idempotencyKey);

    List<AiRequest> findByRequestTypeAndStatusOrderByIdAsc(
            AiRequestType requestType, AiRequestStatus status, Limit limit);
}
