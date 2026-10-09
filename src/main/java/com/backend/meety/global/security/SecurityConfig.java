package com.backend.meety.global.security;

import com.backend.meety.global.config.CorsProperties;
import com.backend.meety.global.config.InternalApiProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final long PREFLIGHT_CACHE_SECONDS = 3600L;
    private static final String PROMETHEUS_ENDPOINT = "/actuator/prometheus";
    private static final String[] API_DOCS_URLS = {"/v3/api-docs/**"};
    private static final String[] SWAGGER_UI_URLS = {
            "/swagger-ui/**",
            "/swagger-ui.html"
    };
    private static final String SPRINGDOC_API_DOCS_ENABLED = "springdoc.api-docs.enabled";
    private static final String SPRINGDOC_SWAGGER_UI_ENABLED = "springdoc.swagger-ui.enabled";
    private static final String INTERNAL_API_URLS = "/internal/**";

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CorsProperties corsProperties;
    private final ObjectProvider<ManagementServerProperties> managementServerProperties;
    private final Environment environment;
    private final InternalApiProperties internalApiProperties;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain internalApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(INTERNAL_API_URLS)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(new InternalApiKeyFilter(internalApiProperties.key()),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // TODO: CSRF 토큰 검증 정책 확정 후 활성화
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    if (isApiDocsEnabled()) {
                        auth.requestMatchers(API_DOCS_URLS).permitAll();
                    } else {
                        auth.requestMatchers(API_DOCS_URLS).denyAll();
                    }
                    if (isSwaggerUiEnabled()) {
                        auth.requestMatchers(SWAGGER_UI_URLS).permitAll();
                    } else {
                        auth.requestMatchers(SWAGGER_UI_URLS).denyAll();
                    }
                    auth.requestMatchers(PermitAllUrls.URLS).permitAll()
                            .requestMatchers(this::isManagementPortMetricsRequest).permitAll()
                            .anyRequest().authenticated();
                })
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, accessTokenBlacklist),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private boolean isManagementPortMetricsRequest(HttpServletRequest request) {
        ManagementServerProperties properties = managementServerProperties.getIfAvailable();
        Integer managementPort = properties == null ? null : properties.getPort();
        return managementPort != null
                && request.getLocalPort() == managementPort
                && PROMETHEUS_ENDPOINT.equals(request.getRequestURI());
    }

    private boolean isApiDocsEnabled() {
        return environment.getProperty(SPRINGDOC_API_DOCS_ENABLED, Boolean.class, false);
    }

    private boolean isSwaggerUiEnabled() {
        return environment.getProperty(SPRINGDOC_SWAGGER_UI_ENABLED, Boolean.class, false);
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(List.of(CorsConfiguration.ALL));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(PREFLIGHT_CACHE_SECONDS);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
