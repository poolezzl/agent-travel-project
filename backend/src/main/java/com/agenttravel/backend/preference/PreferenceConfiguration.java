package com.agenttravel.backend.preference;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 ALG-12 偏好记忆参数（app.preferences.*）。
 * 参数默认值见 docs/alg-12-parameter-decision.md，待 R1/R3 会签。
 */
@Configuration
@EnableConfigurationProperties(PreferenceProperties.class)
public class PreferenceConfiguration {
}
