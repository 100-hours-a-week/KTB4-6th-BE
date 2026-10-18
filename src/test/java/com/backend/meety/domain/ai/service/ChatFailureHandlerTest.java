package com.backend.meety.domain.ai.service;

import static com.backend.meety.domain.recording.RecordingFixtures.credit;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.credit.TestCreditPolicy;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.team.entity.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatFailureHandlerTest {

    private final CreditLedgerRepository ledgers = mock(CreditLedgerRepository.class);
    private final ChatFailureHandler handler = new ChatFailureHandler(ledgers, TestCreditPolicy.DEFAULT);

    private final Team team = team();
    private final AiRequest request = withId(AiRequest.create(team, member(team), "key", AiRequestType.CHAT), 900L);

    @Test
    @DisplayName("차감 원장이 있으면 실패 처리와 함께 크레딧을 돌려준다")
    void restoresChargedCredit() {
        TeamCredit credit = credit(team, 9L);
        when(ledgers.existsByIdempotencyKey("USE:AI_CHAT:900")).thenReturn(true);

        handler.fail(request, credit);

        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(credit.getBalance()).isEqualTo(10L);
    }

    @Test
    @DisplayName("차감 원장이 없으면 실패 처리만 하고 크레딧은 그대로 둔다")
    void keepsCreditWhenNotCharged() {
        TeamCredit credit = credit(team, 9L);

        handler.fail(request, credit);

        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(credit.getBalance()).isEqualTo(9L);
        verify(ledgers, never()).save(any());
    }
}
