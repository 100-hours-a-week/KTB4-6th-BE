package com.backend.meety.domain.transcript.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(TranscriptSpeakerIdMySqlIntegrationTest.DatabaseConfig.class)
@EnabledIfEnvironmentVariable(named = "TRANSCRIPT_SPEAKER_TEST_DB_URL", matches = ".+")
class TranscriptSpeakerIdMySqlIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = TranscriptSegmentRepository.class)
    static class DatabaseConfig {

        @Bean
        DataSource dataSource() {
            String url = System.getenv("TRANSCRIPT_SPEAKER_TEST_DB_URL");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/transcript_speaker_test_[a-z0-9_]+(?:\\?.*)?")) {
                throw new IllegalArgumentException("A disposable local transcript_speaker_test_* database is required");
            }
            return new DriverManagerDataSource(url,
                    System.getenv().getOrDefault("TRANSCRIPT_SPEAKER_TEST_DB_USER", "root"),
                    System.getenv().getOrDefault("TRANSCRIPT_SPEAKER_TEST_DB_PASSWORD", ""));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.backend.meety.domain", "com.backend.meety.global.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }

    @Autowired
    private TranscriptSegmentRepository transcriptSegments;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("AI speakerId는 MySQL 재조회와 종료 후 화자 매핑 뒤에도 원본 값을 유지한다")
    void speakerIdSurvivesDatabaseReloadAndSpeakerMapping() {
        // 테스트 목적:
        // 실시간 전사의 원본 AI 화자 번호가 DB에 저장되어 새로고침 후 조회되고,
        // 별도 화자 매핑이 적용되어도 해당 번호가 바뀌지 않는지 검증한다.

        // given
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long meetingId = transaction.execute(status -> {
            User user = User.create();
            Team team = Team.create("전사 화자 저장 테스트");
            entityManager.persist(user);
            entityManager.persist(team);
            TeamMember member = TeamMember.createLeader(user, team, "녹음 시작자");
            entityManager.persist(member);
            Meeting meeting = Meeting.create(team, member, "회의", "전사", null,
                    LocalDateTime.of(2026, 10, 25, 15, 0), 30);
            entityManager.persist(meeting);
            entityManager.persist(TranscriptSegment.createFinal(
                    meeting, "1:1", 1L, "첫 발화", 0L, 1000L,
                    LocalDateTime.of(2026, 10, 25, 6, 0), 0L));
            entityManager.persist(TranscriptSegment.createFinal(
                    meeting, "1:2", 2L, "화자 미확인", 1000L, 2000L,
                    LocalDateTime.of(2026, 10, 25, 6, 1), null));
            entityManager.flush();
            return meeting.getId();
        });

        // when
        List<TranscriptSegmentResponse> reloaded = transaction.execute(status ->
                transcriptSegments.findAllByMeetingIdOrderBySequence(meetingId).stream()
                        .map(TranscriptSegmentResponse::from)
                        .toList());
        transaction.executeWithoutResult(status -> {
            TranscriptSegment first = transcriptSegments.findAllByMeetingIdOrderBySequence(meetingId).get(0);
            TranscriptSpeaker speaker = TranscriptSpeaker.create(first.getMeeting(), "화자 1");
            entityManager.persist(speaker);
            first.assignSpeaker(speaker);
        });
        List<TranscriptSegmentResponse> afterMapping = transaction.execute(status ->
                transcriptSegments.findAllByMeetingIdOrderBySequence(meetingId).stream()
                        .map(TranscriptSegmentResponse::from)
                        .toList());

        // then
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(0).speakerId()).isZero();
        assertThat(reloaded.get(1).speakerId()).isNull();
        assertThat(afterMapping.get(0).speakerId()).isZero();
        assertThat(afterMapping.get(0).speakerDisplayName()).isEqualTo("화자 1");
    }
}
