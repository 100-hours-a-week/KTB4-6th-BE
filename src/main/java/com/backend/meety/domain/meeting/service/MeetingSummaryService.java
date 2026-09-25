package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.exception.CreditException;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.dto.SummaryCreateResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.exception.SummaryErrorCode;
import com.backend.meety.domain.meeting.exception.SummaryException;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingSummaryService {

    private static final List<AiRequestStatus> PROCESSING_STATUSES = List.of(
            AiRequestStatus.ACCEPTED, AiRequestStatus.PROCESSING
    );

    private final MeetingRepository meetingRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final MeetingSummaryRepository meetingSummaryRepository;
    private final AiRequestRepository aiRequestRepository;
    private final TeamCreditRepository teamCreditRepository;
    private final CreditLedgerRepository creditLedgerRepository;

    @Transactional
    public SummaryCreateResponse requestSummary(Long userId, Long meetingId, String idempotencyKey) {
        AiRequest existing = aiRequestRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return alreadyAcceptedResponse(existing);
        }

        Meeting meeting = findMeeting(meetingId);
        TeamMember member = findActiveMember(userId, meeting);
        validateSummarizable(meeting);

        TeamCredit credit = lockCredit(meeting.getTeam().getId());
        credit.validateCanUse(CreditPolicy.SUMMARY_REGENERATE_COST);

        AiRequest aiRequest = aiRequestRepository.save(
                AiRequest.create(meeting.getTeam(), member, idempotencyKey, AiRequestType.SUMMARY));
        useCredit(credit, meeting.getTeam(), aiRequest.getId());
        MeetingSummary summary = createNextVersion(aiRequest, meeting);
        return SummaryCreateResponse.of(summary, credit.getBalance());
    }

    private Meeting findMeeting(Long meetingId) {
        return meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
    }

    private TeamMember findActiveMember(Long userId, Meeting meeting) {
        return teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        meeting.getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED));
    }

    private void validateSummarizable(Meeting meeting) {
        if (meeting.getStatus() != MeetingStatus.COMPLETED) {
            throw new SummaryException(SummaryErrorCode.MEETING_NOT_COMPLETED);
        }
        if (!transcriptSegmentRepository.existsByMeetingId(meeting.getId())) {
            throw new SummaryException(SummaryErrorCode.TRANSCRIPT_EMPTY);
        }
        if (meetingSummaryRepository.existsByMeetingIdAndAiRequestStatusIn(meeting.getId(), PROCESSING_STATUSES)) {
            throw new SummaryException(SummaryErrorCode.SUMMARY_ALREADY_PROCESSING);
        }
    }

    private TeamCredit lockCredit(Long teamId) {
        return teamCreditRepository.findByTeamIdForUpdate(teamId)
                .orElseThrow(() -> {
                    log.error("요약 재생성에 필요한 팀 크레딧 행이 없습니다. teamId={}", teamId);
                    return new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND);
                });
    }

    private void useCredit(TeamCredit credit, Team team, Long aiRequestId) {
        credit.use(CreditPolicy.SUMMARY_REGENERATE_COST);
        creditLedgerRepository.save(CreditLedger.useForSummary(
                team, aiRequestId, CreditPolicy.SUMMARY_REGENERATE_COST, credit.getBalance()));
    }

    private MeetingSummary createNextVersion(AiRequest aiRequest, Meeting meeting) {
        long version = meetingSummaryRepository.countByMeetingId(meeting.getId()) + 1;
        try {
            return meetingSummaryRepository.saveAndFlush(
                    MeetingSummary.createPending(aiRequest, meeting.getTeam(), meeting, version));
        } catch (DataIntegrityViolationException e) {
            throw new SummaryException(SummaryErrorCode.SUMMARY_ALREADY_PROCESSING);
        }
    }

    private SummaryCreateResponse alreadyAcceptedResponse(AiRequest aiRequest) {
        MeetingSummary summary = meetingSummaryRepository.findByAiRequestId(aiRequest.getId())
                .orElseThrow(() -> new SummaryException(SummaryErrorCode.SUMMARY_REQUEST_FAILED));
        long balance = teamCreditRepository.findByTeamIdAndDeletedAtIsNull(aiRequest.getTeam().getId())
                .map(TeamCredit::getBalance)
                .orElseThrow(() -> {
                    log.error("팀 크레딧 행이 없습니다. teamId={}", aiRequest.getTeam().getId());
                    return new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND);
                });
        return SummaryCreateResponse.of(summary, balance);
    }
}
