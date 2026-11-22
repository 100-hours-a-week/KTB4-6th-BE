package com.backend.meety.domain.ai.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

class AiResponseConsumerControlTest {

    private final RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final Connection connection = mock(Connection.class);
    private final AiResponseConsumerControl control = new AiResponseConsumerControl(registry, dataSource);

    @BeforeEach
    void setUp() {
        when(registry.getListenerContainer(AiResponseConsumer.LISTENER_ID)).thenReturn(container);
    }

    @Test
    @DisplayName("DB 저장 실패로 중단 요청이 여러 번 와도 Consumer는 한 번만 멈춘다")
    void stopsOnce() {
        control.stopForDatabaseFailure();
        control.stopForDatabaseFailure();

        verify(container, timeout(1_000).times(1)).stop();
    }

    @Test
    @DisplayName("DB 저장 실패로 멈춘 뒤 DB가 회복되면 Consumer를 재시작한다")
    void resumesWhenDatabaseRecovers() throws SQLException {
        control.stopForDatabaseFailure();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        control.resumeIfDatabaseAvailable();

        verify(container).start();
    }

    @Test
    @DisplayName("DB가 아직 회복되지 않으면 Consumer를 재시작하지 않는다")
    void keepsStoppedWhileDatabaseUnavailable() throws SQLException {
        control.stopForDatabaseFailure();
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));

        control.resumeIfDatabaseAvailable();

        verify(container, never()).start();
    }

    @Test
    @DisplayName("DB 저장 실패로 멈춘 적이 없으면 Consumer를 재시작하지 않는다")
    void doesNotStartConsumerStoppedForOtherReasons() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        control.resumeIfDatabaseAvailable();

        verify(container, never()).start();
    }

    @Test
    @DisplayName("재개한 뒤 다시 실패하면 다시 중단할 수 있다")
    void canStopAgainAfterResume() throws SQLException {
        control.stopForDatabaseFailure();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        control.resumeIfDatabaseAvailable();

        control.stopForDatabaseFailure();

        verify(container, timeout(1_000).times(2)).stop();
    }
}
