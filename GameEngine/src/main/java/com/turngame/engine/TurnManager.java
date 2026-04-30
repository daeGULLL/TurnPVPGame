package com.turngame.engine;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TurnManager {
    private final List<String> turnOrder;
    private final Set<String> readyPlayers;
    private int windowIndex;
    private final int windowDurationSeconds;

    public TurnManager(List<String> turnOrder) {
        this(turnOrder, 1);
    }

    public TurnManager(List<String> turnOrder, int windowDurationSeconds) {
        if (turnOrder == null || turnOrder.isEmpty()) {
            throw new IllegalArgumentException("turnOrder must not be empty");
        }
        this.turnOrder = List.copyOf(turnOrder);
        this.readyPlayers = new HashSet<>();
        this.windowIndex = 0;
        this.windowDurationSeconds = Math.max(1, windowDurationSeconds);
    }

    public String currentPlayerId() {
        return turnOrder.get(windowIndex % turnOrder.size());
    }

    public int currentWindowIndex() {
        return windowIndex;
    }

    public int windowDurationSeconds() {
        return windowDurationSeconds;
    }

    public boolean markReady(String playerId) {
        if (!turnOrder.contains(playerId)) {
            return false;
        }
        return readyPlayers.add(playerId);
    }

    public boolean isReady(String playerId) {
        return readyPlayers.contains(playerId);
    }

    public Set<String> readyPlayers() {
        return Collections.unmodifiableSet(readyPlayers);
    }

    public boolean allReady() {
        return readyPlayers.size() == turnOrder.size();
    }

    public void nextTurn() {
        windowIndex += 1;
        readyPlayers.clear();
    }
}
