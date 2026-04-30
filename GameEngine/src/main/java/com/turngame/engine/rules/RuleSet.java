package com.turngame.engine.rules;

import com.turngame.engine.GameSession;
import com.turngame.engine.command.GameAction;

import java.util.Optional;

public interface RuleSet {
    boolean validate(GameAction action, GameSession session);
    void apply(GameAction action, GameSession session);
    Optional<String> checkWin(GameSession session);
}
