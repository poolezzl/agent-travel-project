package com.agenttravel.backend.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class SensitiveDataSanitizer {

    private static final Set<String> BLOCKED_KEYS = Set.of(
            "password", "password_hash", "token", "access_token", "refresh_token", "authorization",
            "api_key", "apikey", "secret", "client_secret", "prompt", "raw_prompt",
            "provider_response", "raw_body", "phone", "email", "address", "precise_location");

    public Map<String, Object> sanitize(Map<String, Object> source) {
        return map(source);
    }

    private Map<String, Object> map(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            result.put(name, blocked(name) ? "[REDACTED]" : value(value));
        });
        return result;
    }

    private Object value(Object value) {
        if (value instanceof Map<?, ?> nested) {
            return map(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(value(item)));
            return result;
        }
        return value;
    }

    private boolean blocked(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
        return BLOCKED_KEYS.stream().anyMatch(normalized::contains);
    }
}
