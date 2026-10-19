package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.global.config.AuthCookieProperties;
import com.backend.meety.global.config.CorsProperties;
import com.backend.meety.global.config.InternalApiProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ActuatorPortSecurityIntegrationTest {

    @Nested
    @ActiveProfiles("staging")
    @DisplayName("staging 단일 포트 Actuator")
    class Staging extends EndpointChecks {
    }

    @Nested
    @ActiveProfiles("prod")
    @DisplayName("prod 단일 포트 Actuator")
    class Prod extends EndpointChecks {
    }

    @SpringBootTest(
            classes = TestApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "cors.allowed-origins=http://localhost",
                    "auth.access-token-cookie-name=accessToken",
                    "internal-api.key=actuator-port-test-key",
                    "springdoc.api-docs.enabled=false",
                    "springdoc.swagger-ui.enabled=false",
                    "sentry.dsn="
            }
    )
    abstract static class EndpointChecks {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        @Autowired
        private Environment environment;

        @MockitoBean
        private JwtTokenProvider jwtTokenProvider;

        @MockitoBean
        private AccessTokenBlacklist accessTokenBlacklist;

        @Test
        @DisplayName("관리 포트를 분리하지 않고 기존 API 포트의 헬스체크를 유지한다")
        void healthRemainsOnApplicationPort() throws Exception {
            // 테스트 목적:
            // 배포 헬스체크가 사용하는 기존 API 포트에서
            // 인증 없이 실제 Actuator health 응답을 받을 수 있는지 검증한다.

            // given
            HttpRequest request = request("/actuator/health").GET().build();

            // when
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // then
            assertThat(environment.getProperty("management.server.port")).isNull();
            assertThat(environment.getProperty("local.management.port"))
                    .isEqualTo(environment.getProperty("local.server.port"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        }

        @Test
        @DisplayName("Prometheus는 별도 관리 포트나 인증 없이 API 포트에서 지표를 수집한다")
        void prometheusCanScrapeApplicationPort() throws Exception {
            // 테스트 목적:
            // 수집 서버의 쿠키 없는 GET 요청이 같은 API 포트에서
            // 실제 Prometheus 형식의 지표를 받을 수 있는지 검증한다.

            // given
            HttpRequest request = request("/actuator/prometheus").GET().build();

            // when
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("jvm_memory_used_bytes");
        }

        @ParameterizedTest
        @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
        @DisplayName("Prometheus의 GET 이외 요청은 인증 없이 허용하지 않는다")
        void nonGetMetricsRequestsRemainProtected(String method) throws Exception {
            // 테스트 목적:
            // 지표 수집 허용 범위를 GET으로 제한해
            // 다른 HTTP 메서드까지 인증 예외가 확대되지 않는지 검증한다.

            // given
            HttpRequest request = request("/actuator/prometheus")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();

            // when
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // then
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("AUTHENTICATION_REQUIRED");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/actuator/env", "/actuator/metrics", "/actuator/prometheus/other", "/api/v1/notifications",
                "/internal/v1/qna/meetings/1/summaries"
        })
        @DisplayName("다른 Actuator 경로와 일반 API의 인증 요구를 유지한다")
        void otherEndpointsRemainProtected(String path) throws Exception {
            // 테스트 목적:
            // 단일 지표 경로 허용으로 다른 관리 경로나
            // 기존 일반 API의 인증이 우회되지 않는지 검증한다.

            // given
            HttpRequest request = request(path).GET().build();

            // when
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // then
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("AUTHENTICATION_REQUIRED");
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + environment.getProperty("local.server.port") + path))
                    .timeout(Duration.ofSeconds(5));
        }
    }

    @TestComponent
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, OAuth2ClientAutoConfiguration.class})
    @EnableConfigurationProperties({CorsProperties.class, AuthCookieProperties.class, InternalApiProperties.class})
    @Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class})
    static class TestApplication {
    }
}
