package com.backend.meety.global.security;

import com.backend.meety.global.config.CorsProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CorsProperties corsProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // TODO: CSRF 토큰 검증 정책 확정 후 활성화
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PermitAllUrls.URLS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, accessTokenBlacklist),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(List.of(CorsConfiguration.ALL));
        // 인증 쿠키를 함께 보내야 하므로 허용 Origin에 와일드카드를 사용할 수 없다.
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(PREFLIGHT_CACHE_SECONDS);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
