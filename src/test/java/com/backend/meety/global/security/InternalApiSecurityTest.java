package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class InternalApiSecurityTest {

    private static final String LOCAL_INTERNAL_API_KEY = "local-internal-api-key";
    private static final String SUMMARY_PATH = "/internal/v1/qna/meetings/1/summaries";

    @Autowired
    private Environment environment;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("공유 키 없이 내부 API를 호출하면 401이다")
    void rejectsWithoutKey() throws Exception {
        HttpResponse<String> response = get(HttpRequest.newBuilder(uri())
                .header(InternalApiHeader.AI_REQUEST_ID, "999999"));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("공유 키가 맞으면 JWT 없이도 인증을 통과해 권한 검사까지 간다")
    void passesWithKey() throws Exception {
        HttpResponse<String> response = get(HttpRequest.newBuilder(uri())
                .header(InternalApiHeader.API_KEY, LOCAL_INTERNAL_API_KEY)
                .header(InternalApiHeader.AI_REQUEST_ID, "999999"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("AI_REQUEST_ACCESS_DENIED");
    }

    private URI uri() {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + SUMMARY_PATH);
    }

    private HttpResponse<String> get(HttpRequest.Builder builder) throws Exception {
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
