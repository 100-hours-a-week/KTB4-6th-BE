package com.backend.meety.domain.team.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.team.entity.Team;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

class TeamRepositoryTest {

    @Test
    @DisplayName("팀 조회는 PESSIMISTIC_WRITE lock을 사용한다")
    void findByIdForUpdateUsesPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = TeamRepository.class
                .getMethod("findByIdForUpdate", Long.class)
                .getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(TeamRepository.class.getMethod("findByIdForUpdate", Long.class).getReturnType())
                .isEqualTo(Optional.class);
    }
}
