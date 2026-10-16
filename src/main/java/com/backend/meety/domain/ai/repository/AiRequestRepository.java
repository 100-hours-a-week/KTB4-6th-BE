package com.backend.meety.domain.ai.repository;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import java.time.LocalDateTime;
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
            set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING,
                a.processingToken = :processingToken,
                a.updatedAt = CURRENT_TIMESTAMP
            where a.id = :id
              and a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.ACCEPTED
            """)
    int claimAccepted(
            @Param("id") Long id,
            @Param("processingToken") String processingToken);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiRequest a
            set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.COMPLETED,
                a.processingToken = null,
                a.updatedAt = CURRENT_TIMESTAMP
            where a.id = :id
              and a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING
              and a.processingToken = :processingToken
            """)
    int completeProcessing(
            @Param("id") Long id,
            @Param("processingToken") String processingToken);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiRequest a
            set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.ACCEPTED,
                a.retryCount = a.retryCount + 1,
                a.processingToken = null,
                a.updatedAt = CURRENT_TIMESTAMP
            where a.id = :id
              and a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING
              and a.processingToken = :processingToken
              and a.retryCount < :lastRetryCount
            """)
    int retryProcessing(
            @Param("id") Long id,
            @Param("processingToken") String processingToken,
            @Param("lastRetryCount") long lastRetryCount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiRequest a
            set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.FAILED,
                a.retryCount = case
                    when a.retryCount < :maxRetryCount then a.retryCount + 1
                    else a.retryCount
                end,
                a.failureReason = com.backend.meety.domain.ai.entity.AiFailureReason.AI_CALL_FAILED,
                a.processingToken = null,
                a.updatedAt = CURRENT_TIMESTAMP
            where a.id = :id
              and a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING
              and a.processingToken = :processingToken
              and a.retryCount >= :lastRetryCount
            """)
    int failRetryExhaustedProcessing(
            @Param("id") Long id,
            @Param("processingToken") String processingToken,
            @Param("lastRetryCount") long lastRetryCount,
            @Param("maxRetryCount") long maxRetryCount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiRequest a
            set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.FAILED,
                a.failureReason = com.backend.meety.domain.ai.entity.AiFailureReason.AI_CALL_FAILED,
                a.processingToken = null,
                a.updatedAt = CURRENT_TIMESTAMP
            where a.id = :id
              and a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING
              and a.processingToken = :processingToken
            """)
    int failProcessing(
            @Param("id") Long id,
            @Param("processingToken") String processingToken);

    @Query("""
            select new com.backend.meety.domain.ai.repository.AiRequestRecoveryCandidate(a.id, a.requestType)
            from AiRequest a
            where a.requestType in :requestTypes
              and a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING
              and a.updatedAt <= :staleBefore
              and a.deletedAt is null
            order by a.id asc
            """)
    List<AiRequestRecoveryCandidate> findStaleProcessingCandidates(
            @Param("requestTypes") List<AiRequestType> requestTypes,
            @Param("staleBefore") LocalDateTime staleBefore,
            Limit limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiRequest a
            set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.ACCEPTED,
                a.retryCount = a.retryCount + 1,
                a.processingToken = null,
                a.updatedAt = CURRENT_TIMESTAMP
            where a.id = :id
              and a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING
              and a.updatedAt <= :staleBefore
              and a.retryCount < :lastRetryCount
              and a.deletedAt is null
            """)
    int recoverStaleProcessing(
            @Param("id") Long id,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("lastRetryCount") long lastRetryCount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiRequest a
            set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.FAILED,
                a.retryCount = case
                    when a.retryCount < :maxRetryCount then a.retryCount + 1
                    else a.retryCount
                end,
                a.failureReason = com.backend.meety.domain.ai.entity.AiFailureReason.AI_CALL_FAILED,
                a.processingToken = null,
                a.updatedAt = CURRENT_TIMESTAMP
            where a.id = :id
              and a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING
              and a.updatedAt <= :staleBefore
              and a.retryCount >= :lastRetryCount
              and a.deletedAt is null
            """)
    int failStaleProcessing(
            @Param("id") Long id,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("lastRetryCount") long lastRetryCount,
            @Param("maxRetryCount") long maxRetryCount);
}
