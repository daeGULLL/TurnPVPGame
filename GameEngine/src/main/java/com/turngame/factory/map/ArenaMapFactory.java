package com.turngame.factory.map;

import com.turngame.domain.map.BattleMap;

import java.util.List;

public class ArenaMapFactory extends MapFactory {
    @Override
    public BattleMap createMap() {
    return new BattleMap(
        "arena",
        "Iron Arena",
        "Open battlefield with no cover.",
        3,
        3,
        List.of(
            "...",
            "...",
            "..."
        )
    );
    }
}
