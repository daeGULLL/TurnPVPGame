package com.turngame.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EventBus {
    private final Map<Class<?>, List<EventListener<?>>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService asyncPool = Executors.newFixedThreadPool(2);

    public <T extends GameEvent> void subscribe(Class<T> eventType, EventListener<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @SuppressWarnings("unchecked")
    public <T extends GameEvent> void publish(T event) {
        List<EventListener<?>> list = listeners.getOrDefault(event.getClass(), List.of());
        for (EventListener<?> rawListener : list) {
            EventListener<T> listener = (EventListener<T>) rawListener;
            asyncPool.submit(() -> listener.onEvent(event));
        }
    }
}
