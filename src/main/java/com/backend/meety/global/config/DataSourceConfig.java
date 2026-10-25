package com.backend.meety.global.config;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

@Configuration
public class DataSourceConfig {

    public static final String WRITE_DATA_SOURCE_BEAN_NAME = "writeDataSource";
    public static final String READ_DATA_SOURCE_BEAN_NAME = "readDataSource";
    private static final String WRITE_POOL_NAME = "meety-write";
    private static final String READ_POOL_NAME = "meety-read";

    @Bean(WRITE_DATA_SOURCE_BEAN_NAME)
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource writeDataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        dataSource.setPoolName(WRITE_POOL_NAME);
        return dataSource;
    }

    @Bean(READ_DATA_SOURCE_BEAN_NAME)
    @ConfigurationProperties(prefix = "read-datasource.hikari")
    public HikariDataSource readDataSource(ReadDataSourceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setPoolName(READ_POOL_NAME);
        return dataSource;
    }

    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier(WRITE_DATA_SOURCE_BEAN_NAME) DataSource writeDataSource,
            @Qualifier(READ_DATA_SOURCE_BEAN_NAME) DataSource readDataSource
    ) {
        return new LazyConnectionDataSourceProxy(new ReadWriteRoutingDataSource(writeDataSource, readDataSource));
    }

    @Bean
    public ReadAfterWriteFilter readAfterWriteFilter(ReadAfterWriteProperties properties, Clock clock) {
        return new ReadAfterWriteFilter(properties, clock);
    }
}
