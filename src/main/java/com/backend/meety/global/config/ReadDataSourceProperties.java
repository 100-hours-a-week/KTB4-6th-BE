package com.backend.meety.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "read-datasource")
public record ReadDataSourceProperties(String url, String username, String password) {
}
