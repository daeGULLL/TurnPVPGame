package com.turngame.server.protocol;

import java.util.Map;

public record RequestMessage(String type, String requestId, Map<String, Object> payload) {
}
