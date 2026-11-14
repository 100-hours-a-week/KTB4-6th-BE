package com.backend.meety.domain.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.PublishStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@SpringJUnitConfig(AiRequestPublishColumnsMySqlIntegrationTest.DatabaseConfig.class)
class AiRequestPublishColumnsMySqlIntegrationTest {

    static final String ADD_PUBLISH_COLUMNS = """
            ALTER TABLE ai_requests
                ADD COLUMN publish_status ENUM('PUBLISHED','PUBLISHING','UNPUBLISHED') NULL,
                ADD COLUMN message_payload JSON NULL,
                ADD COLUMN publish_routing_key VARCHAR(50) NULL,
                ADD COLUMN next_publish_at DATETIME(6) NULL,
                ADD COLUMN publish_started_at DATETIME(6) NULL,
                ADD COLUMN published_at DATETIME(6) NULL,
                ALGORITHM=INSTANT
            """;

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = AiRequestRepository.class)
    static class DatabaseConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            return entityManagerFactory(dataSource, "create-drop");
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        static LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource, String schemaAction) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.backend.meety.domain", "com.backend.meety.global.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", schemaAction));
            return factory;
        }
    }

    @Autowired
    private AiRequestRepository aiRequests;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @PersistenceContext
    private EntityManager entityManager;

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void prepareDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("delete from ai_requests");
        jdbc.update("delete from team_members");
        jdbc.update("delete from teams");
        jdbc.update("delete from users");
    }

    @Test
    @DisplayName("새 AI 요청은 발행 관리 컬럼이 모두 NULL로 저장된다")
    void newRequestHasNoPublishState() {
        long requestId = createRequest();

        AiRequest request = findRequest(requestId);

        assertThat(request.getPublishStatus()).isNull();
        assertThat(request.getMessagePayload()).isNull();
        assertThat(request.getPublishRoutingKey()).isNull();
        assertThat(request.getNextPublishAt()).isNull();
        assertThat(request.getPublishStartedAt()).isNull();
        assertThat(request.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("발행 관리 컬럼 값이 엔티티로 그대로 조회된다")
    void readsPublishColumns() {
        long requestId = createRequest();
        LocalDateTime nextPublishAt = LocalDateTime.of(2026, 11, 14, 10, 0, 0, 123_456_000);
        LocalDateTime publishStartedAt = nextPublishAt.plusSeconds(1);
        LocalDateTime publishedAt = nextPublishAt.plusSeconds(2);
        jdbc.update("""
                        update ai_requests
                        set publish_status = 'PUBLISHED', message_payload = ?, publish_routing_key = 'summary',
                            next_publish_at = ?, publish_started_at = ?, published_at = ?
                        where id = ?
                        """,
                "{\"schemaVersion\": 1, \"input\": {\"meetingId\": 7}}",
                nextPublishAt, publishStartedAt, publishedAt, requestId);

        AiRequest request = findRequest(requestId);

        assertThat(request.getPublishStatus()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(request.getMessagePayload()).contains("\"schemaVersion\": 1").contains("\"meetingId\": 7");
        assertThat(request.getPublishRoutingKey()).isEqualTo("summary");
        assertThat(request.getNextPublishAt()).isEqualTo(nextPublishAt);
        assertThat(request.getPublishStartedAt()).isEqualTo(publishStartedAt);
        assertThat(request.getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    @DisplayName("운영 반영 SQL을 적용하면 기존 행은 NULL로 남고 엔티티 스키마 검증을 통과한다")
    void migrationKeepsExistingRowsUnpublishable() throws Exception {
        long legacyRequestId = createRequest();
        jdbc.execute("""
                ALTER TABLE ai_requests
                    DROP COLUMN publish_status, DROP COLUMN message_payload, DROP COLUMN publish_routing_key,
                    DROP COLUMN next_publish_at, DROP COLUMN publish_started_at, DROP COLUMN published_at
                """);

        jdbc.execute(ADD_PUBLISH_COLUMNS);

        assertThat(jdbc.queryForObject(
                "select count(*) from ai_requests where id = ? and publish_status is null and message_payload is null",
                Integer.class, legacyRequestId)).isOne();
        assertThat(findRequest(legacyRequestId).getPublishStatus()).isNull();
        LocalContainerEntityManagerFactoryBean validator = DatabaseConfig.entityManagerFactory(dataSource, "validate");
        assertThatCode(() -> {
            validator.afterPropertiesSet();
            validator.destroy();
        }).doesNotThrowAnyException();
    }

    private long createRequest() {
        return transaction.execute(status -> {
            User user = User.create();
            Team team = Team.create("발행 컬럼 테스트");
            entityManager.persist(user);
            entityManager.persist(team);
            TeamMember member = TeamMember.createLeader(user, team, "요청자");
            entityManager.persist(member);
            AiRequest request = AiRequest.create(team, member, "publish-" + UUID.randomUUID(), AiRequestType.SUMMARY);
            entityManager.persist(request);
            entityManager.flush();
            return request.getId();
        });
    }

    private AiRequest findRequest(long requestId) {
        return transaction.execute(status -> aiRequests.findById(requestId).orElseThrow());
    }
}
