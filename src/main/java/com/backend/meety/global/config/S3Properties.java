package com.backend.meety.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws.s3")
public record S3Properties(
        String bucket,
        String region
) {
}
