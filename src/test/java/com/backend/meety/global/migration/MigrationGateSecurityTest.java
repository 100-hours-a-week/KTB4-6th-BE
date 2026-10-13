package com.backend.meety.global.migration;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class MigrationGateSecurityTest {
    private static final String TOKEN = "test-only-not-a-real-secret-token-123456789";

    @Test
    void everyAdminOperationRequiresTokenAndCanChangeState() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("migration.write-gate.enabled", "true")));
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            String path = "/internal/db-migration/gate";
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(put(path + "/open")).andExpect(status().isUnauthorized());
            mvc.perform(put(path + "/closed").header("Authorization", "Bearer wrong"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(true));
            mvc.perform(put(path + "/open").header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(false));
            mvc.perform(put(path + "/closed").header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.drained").value(true));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({MigrationGateController.class, MigrationGateSecurityConfiguration.class})
    static class Config {
        @Bean MigrationGateProperties properties() {
            return new MigrationGateProperties(true, true, Duration.ofSeconds(1), 2, TOKEN);
        }
        @Bean MigrationWriteGate gate(MigrationGateProperties properties) {
            return new MigrationWriteGate(properties);
        }
    }
}
