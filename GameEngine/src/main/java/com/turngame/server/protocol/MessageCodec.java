package com.turngame.server.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class MessageCodec {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private MessageCodec() {
    }

    public static RequestMessage decodeRequest(String line) {
        RequestMessage request = GSON.fromJson(line, RequestMessage.class);
        if (request == null) {
            return new RequestMessage("UNKNOWN", "none", java.util.Map.of());
        }
        return new RequestMessage(
                request.type() == null ? "UNKNOWN" : request.type(),
                request.requestId() == null ? "none" : request.requestId(),
                request.payload() == null ? java.util.Map.of() : request.payload());
    }

    public static String encodeResponse(ResponseMessage response) {
        return GSON.toJson(response);
    }
}
