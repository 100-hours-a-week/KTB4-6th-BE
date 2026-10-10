package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    @Test
    @DisplayName("Swagger 서버 주소를 상대 경로로 지정해 페이지를 연 주소와 같은 scheme·host로 요청한다")
    void useRelativeServerUrl() {
        OpenAPI openAPI = new OpenApiConfig().openAPI();

        assertThat(openAPI.getServers())
                .extracting(Server::getUrl)
                .containsExactly("/");
    }
}
