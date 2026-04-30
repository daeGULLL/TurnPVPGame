package com.turngame.event;

public record GameEndedEvent(String matchId, String winnerId) implements GameEvent {
}
