package com.magefight.content.model;

import java.util.concurrent.ThreadLocalRandom;

public enum MageArchetype {
    APPRENTICE("마도수련생", 0, 10, 30, 10, 30, 10, 30),
    ELEMENTALIST("원소술사", 1, 10, 20, 14, 30, 18, 30),
    RUNE_SCHOLAR("룬 마도사", 2, 10, 18, 10, 24, 20, 30);

    private final String displayName;
    private final int tier;
    private final int strengthMin;
    private final int strengthMax;
    private final int agilityMin;
    private final int agilityMax;
    private final int intelligenceMin;
    private final int intelligenceMax;

    MageArchetype(
            String displayName,
            int tier,
            int strengthMin,
            int strengthMax,
            int agilityMin,
            int agilityMax,
            int intelligenceMin,
            int intelligenceMax
    ) {
        this.displayName = displayName;
        this.tier = tier;
        this.strengthMin = strengthMin;
        this.strengthMax = strengthMax;
        this.agilityMin = agilityMin;
        this.agilityMax = agilityMax;
        this.intelligenceMin = intelligenceMin;
        this.intelligenceMax = intelligenceMax;
    }

    public String displayName() {
        return displayName;
    }

    public int tier() {
        return tier;
    }

    public int rollStrength(ThreadLocalRandom random) {
        return random.nextInt(strengthMin, strengthMax + 1);
    }

    public int rollAgility(ThreadLocalRandom random) {
        return random.nextInt(agilityMin, agilityMax + 1);
    }

    public int rollIntelligence(ThreadLocalRandom random) {
        return random.nextInt(intelligenceMin, intelligenceMax + 1);
    }
}