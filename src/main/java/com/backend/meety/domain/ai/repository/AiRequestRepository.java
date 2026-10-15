package com.backend.meety.domain.ai.repository;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiRequestRepository extends JpaRepository<AiRequest, Long> {

    Optional<AiRequest> findByIdempotencyKey(String idempotencyKey);

    List<AiRequest> findByRequestTypeAndStatusOrderByIdAsc(
            AiRequestType requestType, AiRequestStatus status, Limit limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiRequest a
            set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING
            where a.id = :id
              and a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.ACCEPTED
            """)
    int claimAccepted(@Param("id") Long id);
}
