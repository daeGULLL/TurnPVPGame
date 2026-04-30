package com.turngame.event;

public interface EventListener<T extends GameEvent> {
    void onEvent(T event);
}
