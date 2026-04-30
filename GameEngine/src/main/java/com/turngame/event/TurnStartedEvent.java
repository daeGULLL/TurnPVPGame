package com.turngame.event;

public record TurnStartedEvent(String matchId, String playerId) implements GameEvent {
}
