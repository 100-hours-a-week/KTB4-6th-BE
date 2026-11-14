package com.backend.meety.domain.ai.repository;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import org.testcontainers.mysql.MySQLContainer;

@SpringJUnitConfig(AiRequestMySqlTestSupport.DatabaseConfig.class)
public abstract class AiRequestMySqlTestSupport {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = AiRequestRepository.class)
    public static class DatabaseConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
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
    protected AiRequestRepository aiRequests;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected DataSource dataSource;
    @PersistenceContext
    protected EntityManager entityManager;

    protected JdbcTemplate jdbc;
    protected TransactionTemplate transaction;

    @BeforeEach
    void prepareDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("delete from ai_requests");
        jdbc.update("delete from team_members");
        jdbc.update("delete from teams");
        jdbc.update("delete from users");
    }

    protected long createRequest(AiRequestType requestType) {
        return transaction.execute(status -> {
            User user = User.create();
            Team team = Team.create("AI 요청 테스트");
            entityManager.persist(user);
            entityManager.persist(team);
            TeamMember member = TeamMember.createLeader(user, team, "요청자");
            entityManager.persist(member);
            AiRequest request = AiRequest.create(team, member, "test-" + UUID.randomUUID(), requestType);
            entityManager.persist(request);
            entityManager.flush();
            return request.getId();
        });
    }

    protected AiRequest findRequest(long requestId) {
        return transaction.execute(status -> aiRequests.findById(requestId).orElseThrow());
    }
}
