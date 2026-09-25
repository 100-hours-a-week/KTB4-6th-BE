package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.client.SummaryAiRequest;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditSourceType;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.SummaryPolicy;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryProcessingService {

    private final AiRequestRepository aiRequestRepository;
    private final MeetingSummaryRepository meetingSummaryRepository;
    private final TeamCreditRepository teamCreditRepository;
    private final CreditLedgerRepository creditLedgerRepository;
    private final SummaryAiRequestFactory summaryAiRequestFactory;

    @Transactional
    public Optional<SummaryAiRequest> startProcessing(Long aiRequestId) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElse(null);
        if (aiRequest == null || !aiRequest.isAccepted()) {
            return Optional.empty();
        }
        MeetingSummary summary = meetingSummaryRepository.findByAiRequestId(aiRequestId).orElse(null);
        if (summary == null) {
            log.error("요약 회차 행이 없어 처리할 수 없습니다. aiRequestId={}", aiRequestId);
            aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
            return Optional.empty();
        }
        aiRequest.markProcessing();
        return Optional.of(summaryAiRequestFactory.create(aiRequest, summary.getMeeting()));
    }

    @Transactional
    public void completeProcessing(Long aiRequestId, String content) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElseThrow();
        MeetingSummary summary = meetingSummaryRepository.findByAiRequestId(aiRequestId).orElseThrow();
        summary.complete(content);
        aiRequest.markCompleted();
        log.info("회의 요약이 완료되었습니다. aiRequestId={}, summaryId={}, contentLength={}",
                aiRequestId, summary.getId(), content.length());
    }

    @Transactional
    public void handleRetryableFailure(Long aiRequestId) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElseThrow();
        aiRequest.increaseRetryCount();
        if (aiRequest.getRetryCount() < SummaryPolicy.MAX_RETRY_COUNT) {
            aiRequest.markAccepted();
            log.warn("AI 요약 호출에 실패해 재시도합니다. aiRequestId={}, retryCount={}",
                    aiRequestId, aiRequest.getRetryCount());
            return;
        }
        failPermanently(aiRequest);
    }

    @Transactional
    public void handlePermanentFailure(Long aiRequestId) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElseThrow();
        failPermanently(aiRequest);
    }

    private void failPermanently(AiRequest aiRequest) {
        aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
        restoreCreditIfCharged(aiRequest);
        log.error("AI 요약 호출이 최종 실패했습니다. aiRequestId={}, retryCount={}",
                aiRequest.getId(), aiRequest.getRetryCount());
    }

    private void restoreCreditIfCharged(AiRequest aiRequest) {
        String useKey = CreditLedger.keyOf(
                CreditTransactionType.USE, CreditSourceType.AI_SUMMARY, aiRequest.getId());
        if (!creditLedgerRepository.existsByIdempotencyKey(useKey)) {
            return;
        }
        TeamCredit credit = teamCreditRepository.findByTeamIdForUpdate(aiRequest.getTeam().getId())
                .orElse(null);
        if (credit == null) {
            log.error("크레딧 복구 대상 팀 크레딧 행이 없습니다. teamId={}", aiRequest.getTeam().getId());
            return;
        }
        long restored = credit.earn(CreditPolicy.SUMMARY_REGENERATE_COST);
        creditLedgerRepository.save(CreditLedger.restoreForSummary(
                aiRequest.getTeam(), aiRequest.getId(), restored, credit.getBalance()));
    }
}
