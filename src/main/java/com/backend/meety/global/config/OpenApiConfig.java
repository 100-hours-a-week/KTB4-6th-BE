package com.backend.meety.global.config;

import com.backend.meety.global.security.PermitAllUrls;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;

@Configuration
public class OpenApiConfig {

    private static final String COOKIE_AUTH = "cookieAuth";
    private static final String RELATIVE_SERVER_URL = "/";

    private static final List<String> PUBLIC_API_PATTERNS = Arrays.stream(PermitAllUrls.URLS)
            .filter(url -> url.startsWith("/api/"))
            .toList();

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Meety API")
                        .version("v1"))
                .servers(List.of(new Server().url(RELATIVE_SERVER_URL)))
                .components(new Components()
                        .addSecuritySchemes(COOKIE_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("accessToken")));
    }

    @Bean
    public OpenApiCustomizer cookieAuthOpenApiCustomizer() {
        AntPathMatcher pathMatcher = new AntPathMatcher();
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperations().forEach(operation -> applyCookieAuth(pathMatcher, path, operation)));
        };
    }

    private void applyCookieAuth(AntPathMatcher pathMatcher, String path, Operation operation) {
        if (isPublicApi(pathMatcher, path)) {
            return;
        }
        operation.addSecurityItem(new SecurityRequirement().addList(COOKIE_AUTH));
    }

    private boolean isPublicApi(AntPathMatcher pathMatcher, String path) {
        return PUBLIC_API_PATTERNS.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }
}
