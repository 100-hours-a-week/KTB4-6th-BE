package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(DataSourceRoutingMySqlIntegrationTest.DatabaseConfig.class)
@EnabledIfEnvironmentVariable(named = "READ_REPLICA_TEST_DB_URL", matches = ".+")
class DataSourceRoutingMySqlIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = TranscriptSegmentRepository.class)
    static class DatabaseConfig {

        @Bean(DataSourceConfig.WRITE_DATA_SOURCE_BEAN_NAME)
        CountingDataSource writeDataSource() {
            return new CountingDataSource(pool("test-write"));
        }

        @Bean(DataSourceConfig.READ_DATA_SOURCE_BEAN_NAME)
        CountingDataSource readDataSource() {
            return new CountingDataSource(pool("test-read"));
        }

        @Bean
        @Primary
        DataSource dataSource(
                @Qualifier(DataSourceConfig.WRITE_DATA_SOURCE_BEAN_NAME) DataSource writeDataSource,
                @Qualifier(DataSourceConfig.READ_DATA_SOURCE_BEAN_NAME) DataSource readDataSource
        ) {
            return new DataSourceConfig().dataSource(writeDataSource, readDataSource);
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

        private static HikariDataSource pool(String poolName) {
            String url = System.getenv("READ_REPLICA_TEST_DB_URL");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/read_replica_test_[a-z0-9_]+(?:\\?.*)?")) {
                throw new IllegalArgumentException("A disposable local read_replica_test_* database is required");
            }
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(url);
            dataSource.setUsername(System.getenv().getOrDefault("READ_REPLICA_TEST_DB_USER", "root"));
            dataSource.setPassword(System.getenv().getOrDefault("READ_REPLICA_TEST_DB_PASSWORD", ""));
            dataSource.setPoolName(poolName);
            return dataSource;
        }
    }

    @Autowired
    @Qualifier(DataSourceConfig.WRITE_DATA_SOURCE_BEAN_NAME)
    private CountingDataSource writeDataSource;
    @Autowired
    @Qualifier(DataSourceConfig.READ_DATA_SOURCE_BEAN_NAME)
    private CountingDataSource readDataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private TranscriptSegmentRepository transcriptSegments;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("읽기 전용 트랜잭션은 읽기 풀의 커넥션을 사용한다")
    void readOnlyTransactionUsesReadPool() {
        // 테스트 목적:
        // JpaTransactionManager가 읽기 전용 트랜잭션의 커넥션에 read-only를 먼저 설정해
        // LazyConnectionDataSourceProxy가 첫 쿼리 시점에 읽기 풀을 고르는지 검증한다.

        // given
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        resetCounts();

        // when
        readOnly.executeWithoutResult(status -> transcriptSegments.count());

        // then
        assertThat(writeDataSource.count()).isZero();
        assertThat(readDataSource.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("쓰기 트랜잭션은 안의 조회까지 쓰기 풀의 커넥션을 사용한다")
    void writeTransactionUsesWritePool() {
        // 테스트 목적:
        // 쓰기 트랜잭션은 조회만 하더라도 쓰기 풀을 사용하고 읽기 풀을 사용하지 않는지 검증한다.

        // given
        TransactionTemplate write = new TransactionTemplate(transactionManager);
        resetCounts();

        // when
        write.executeWithoutResult(status -> transcriptSegments.count());

        // then
        assertThat(writeDataSource.count()).isEqualTo(1);
        assertThat(readDataSource.count()).isZero();
    }

    @Test
    @DisplayName("쓰기 트랜잭션 안에서 호출한 Repository 조회는 쓰기 풀을 계속 사용한다")
    void repositoryReadInsideWriteTransactionUsesWritePool() {
        // 테스트 목적:
        // Repository 기본 조회 메서드의 readOnly 트랜잭션이 바깥 쓰기 트랜잭션에 참여해
        // 읽기 풀로 바뀌지 않는지 검증한다.

        // given
        TransactionTemplate write = new TransactionTemplate(transactionManager);
        resetCounts();

        // when
        write.executeWithoutResult(status -> {
            entityManager.createNativeQuery("select 1").getSingleResult();
            transcriptSegments.findAll();
        });

        // then
        assertThat(writeDataSource.count()).isEqualTo(1);
        assertThat(readDataSource.count()).isZero();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 호출한 Repository 기본 조회는 읽기 풀을 사용한다")
    void repositoryReadOutsideTransactionUsesReadPool() {
        // 테스트 목적:
        // Spring Data 기본 조회 메서드는 자체 readOnly 트랜잭션을 사용하므로
        // 트랜잭션 밖에서 호출하면 읽기 풀로 연결된다는 동작을 고정한다.

        // given
        resetCounts();

        // when
        transcriptSegments.count();

        // then
        assertThat(writeDataSource.count()).isZero();
        assertThat(readDataSource.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Master 읽기 표시가 있으면 읽기 전용 트랜잭션도 쓰기 풀을 사용한다")
    void readOnlyTransactionUsesWritePoolWhenMasterForced() {
        // 테스트 목적:
        // 쓰기 직후 요청처럼 Master 읽기 표시가 켜진 동안에는
        // 읽기 전용 트랜잭션도 복제 지연이 없는 쓰기 풀을 사용하는지 검증한다.

        // given
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        resetCounts();
        ReadMasterContext.force();

        // when
        try {
            readOnly.executeWithoutResult(status -> transcriptSegments.count());
        } finally {
            ReadMasterContext.clear();
        }

        // then
        assertThat(writeDataSource.count()).isEqualTo(1);
        assertThat(readDataSource.count()).isZero();
    }

    private void resetCounts() {
        writeDataSource.reset();
        readDataSource.reset();
    }

    static class CountingDataSource extends DelegatingDataSource {

        private final AtomicInteger connections = new AtomicInteger();

        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            return super.getConnection();
        }

        int count() {
            return connections.get();
        }

        void reset() {
            connections.set(0);
        }
    }
}
