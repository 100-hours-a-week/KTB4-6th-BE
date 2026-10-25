package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class DataSourceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, DataSourceConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:mysql://write-host:3306/meety",
                    "spring.datasource.username=writer",
                    "spring.datasource.password=write-secret",
                    "spring.datasource.hikari.maximum-pool-size=10",
                    "read-datasource.url=jdbc:mysql://read-host:3306/meety",
                    "read-datasource.username=reader",
                    "read-datasource.password=read-secret",
                    "read-datasource.hikari.maximum-pool-size=4",
                    "read-after-write.window=3s"
            );

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({DataSourceProperties.class, ReadDataSourceProperties.class,
            ReadAfterWriteProperties.class})
    static class PropertiesConfig {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Test
    @DisplayName("쓰기·읽기 풀은 각자의 접속 정보와 풀 크기 설정을 사용한다")
    void poolsBindTheirOwnSettings() {
        // 테스트 목적:
        // 읽기 풀이 read-datasource 접속 정보와 크기 설정을 받고,
        // 쓰기 풀은 기존 spring.datasource 설정을 그대로 사용하는지 검증한다.

        contextRunner.run(context -> {
            HikariDataSource write = context.getBean(DataSourceConfig.WRITE_DATA_SOURCE_BEAN_NAME, HikariDataSource.class);
            HikariDataSource read = context.getBean(DataSourceConfig.READ_DATA_SOURCE_BEAN_NAME, HikariDataSource.class);

            assertThat(write.getJdbcUrl()).isEqualTo("jdbc:mysql://write-host:3306/meety");
            assertThat(write.getUsername()).isEqualTo("writer");
            assertThat(write.getMaximumPoolSize()).isEqualTo(10);
            assertThat(write.getPoolName()).isEqualTo("meety-write");

            assertThat(read.getJdbcUrl()).isEqualTo("jdbc:mysql://read-host:3306/meety");
            assertThat(read.getUsername()).isEqualTo("reader");
            assertThat(read.getMaximumPoolSize()).isEqualTo(4);
            assertThat(read.getPoolName()).isEqualTo("meety-read");
        });
    }
}
