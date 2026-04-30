package com.turngame.factory.map;

import com.turngame.domain.map.BattleMap;

import java.util.List;

public class RuinsMapFactory extends MapFactory {
    @Override
    public BattleMap createMap() {
    return new BattleMap(
        "ruins",
        "Ancient Ruins",
        "Broken pillars create tactical chokepoints.",
        3,
        3,
        List.of(
            ".P.",
            "P#P",
            ".P."
        )
    );
    }
}
