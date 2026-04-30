package com.turngame.event;

import com.turngame.engine.command.GameAction;

public record ActionAppliedEvent(String matchId, GameAction action) implements GameEvent {
}
