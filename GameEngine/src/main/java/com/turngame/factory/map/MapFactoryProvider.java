package com.turngame.factory.map;

import java.util.List;
import java.util.Random;

public final class MapFactoryProvider {
    private static final List<MapFactory> FACTORIES = List.of(
            new ForestMapFactory(),
            new RuinsMapFactory(),
            new ArenaMapFactory());

    private static final Random RANDOM = new Random();

    private MapFactoryProvider() {
    }

    public static MapFactory randomFactory() {
        return FACTORIES.get(RANDOM.nextInt(FACTORIES.size()));
    }
}
