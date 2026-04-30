package com.turngame.server.protocol;

import java.util.Map;

public record ResponseMessage(String type, String requestId, Map<String, Object> payload) {
}
