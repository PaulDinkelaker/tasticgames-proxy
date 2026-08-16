package de.tasticgames.proxy.bus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tasticgames.client.internal.JacksonSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payloads are flat string maps serialized as JSON objects.
 */
public final class PayloadCodec {

    private static final ObjectMapper MAPPER = JacksonSupport.objectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private PayloadCodec() {
    }

    public static String encode(Map<String, String> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode payload", e);
        }
    }

    public static Map<String, String> decode(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(json, MAP);
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (v != null) {
                    out.put(k, String.valueOf(v));
                }
            });
            return out;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot decode payload: " + e.getOriginalMessage(), e);
        }
    }
}
