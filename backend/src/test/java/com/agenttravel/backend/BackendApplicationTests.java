package com.agenttravel.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class BackendApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoadsWithMockProfile() {
        assertThat(applicationContext).isNotNull();
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("agent-travel-backend");
        assertThat(environment.getProperty("app.external-services.enabled", Boolean.class))
                .isFalse();
    }

    @Test
    void mockProfileDoesNotCreateDataSourceOrRequireDatabase() {
        assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
    }

    @Test
    void healthEndpointReportsMockInfrastructure() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.backend.status").value("UP"))
                .andExpect(jsonPath("$.components.backend.details.databaseMode").value("disabled"))
                .andExpect(jsonPath("$.components.backend.details.externalServices").value("disabled"))
                .andExpect(jsonPath("$.components.db").doesNotExist())
                .andExpect(jsonPath("$.components.flyway").doesNotExist());
    }

    @Test
    void metricsEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }
}
