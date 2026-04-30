package com.turngame.factory.map;

import com.turngame.domain.map.BattleMap;

import java.util.List;

public class ForestMapFactory extends MapFactory {
    @Override
    public BattleMap createMap() {
    return new BattleMap(
        "forest",
        "Emerald Forest",
        "Dense trees reduce long-range vision.",
        3,
        3,
        List.of(
            "T.T",
            ".#.",
            "T.T"
        )
    );
    }
}
