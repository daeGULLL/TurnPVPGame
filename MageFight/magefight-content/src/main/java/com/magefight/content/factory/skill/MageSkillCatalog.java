package com.magefight.content.factory.skill;

import java.util.List;

import com.magefight.content.model.SkillVisualProfile;
import com.magefight.content.model.SkillVisualStyle;
import com.turngame.domain.skill.SkillEffect;
import com.turngame.domain.skill.SkillTemplate;

final class MageSkillCatalog {
    private static final List<SkillEntry> ENTRIES = List.of(
            entry("Firebolt", 10, 1, 1, 1, SkillEffect.AreaType.STATIC, 1,
                    style("#FFB656", "#FFDC91", "#FFCC85", 0.62, 0.34, 2.4f, 7),
                    null, null),
            entry("Frost Nova", 12, 2, 1, 1, SkillEffect.AreaType.STATIC, 2,
                    style("#ABECFF", "#79D2F7", "#AAEAFF", 0.56, 0.40, 2.0f, 6),
                    null, null),
            entry("Blink Cut", 16, 3, 1, 1, SkillEffect.AreaType.DYNAMIC, 2,
                    style("#FFD2D2", "#FFC3C3", "#FFADAD", 0.58, 0.28, 2.3f, 6),
                    null, null),
            entry("Arcane Sigil", 12, 2, 1, 1, SkillEffect.AreaType.STATIC, 1,
                    style("#C9A8FF", "#B794F4", "#D0BCFF", 0.60, 0.30, 2.3f, 6),
                    null, null),
            entry("Rune Ward", 11, 2, 1, 1, SkillEffect.AreaType.STATIC, 2,
                    style("#C5D9FF", "#B3CCFF", "#A7C4FF", 0.58, 0.30, 2.2f, 6),
                    null, null),
            entry("Mana Burst", 18, 3, 1, 1, SkillEffect.AreaType.DYNAMIC, 3,
                    style("#9EF5C6", "#8EE9F6", "#A6F3E1", 0.58, 0.28, 2.5f, 7),
                    null, null),
            entry("LightningBolt", 14, 2, 1, 1, SkillEffect.AreaType.STATIC, 2,
                    style("#FCF7AA", "#D5E9FF", "#C2DAFF", 0.80, 0.18, 2.6f, 5),
                    "/assets/effects/lightning_bolt.png", 26),
            entry("FrostLance", 13, 2, 1, 1, SkillEffect.AreaType.STATIC, 2,
                    style("#ABEFFF", "#9DE7FF", "#AAEAFF", 0.56, 0.40, 2.0f, 6),
                    "/assets/effects/frost_lance.png", 24)
    );

    private MageSkillCatalog() {
    }

    static SkillTemplate templateOrNull(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        String key = skillName.trim();
        for (SkillEntry entry : ENTRIES) {
            if (entry.name().equalsIgnoreCase(key)) {
                return entry.toTemplate();
            }
        }
        return null;
    }

    static List<SkillVisualProfile> visualProfiles() {
        return ENTRIES.stream()
                .map(SkillEntry::toVisualProfile)
                .toList();
    }

    private static SkillEntry entry(
            String name,
            int baseDamage,
            int cooldownTurns,
            int failEnergyCost,
            int successEnergyCost,
            SkillEffect.AreaType areaType,
            int areaRadius,
            SkillVisualStyle style,
            String imagePath,
            Integer imageSize
    ) {
        return new SkillEntry(
                name,
                baseDamage,
                cooldownTurns,
                failEnergyCost,
                successEnergyCost,
                areaType,
                areaRadius,
                style,
                imagePath,
                imageSize
        );
    }

    private static SkillVisualStyle style(
            String coreColorHex,
            String glowColorHex,
            String trailColorHex,
            double launchStartProgress,
            double launchDurationProgress,
            float trailWidth,
            int radius
    ) {
        return new SkillVisualStyle(
                coreColorHex,
                glowColorHex,
                trailColorHex,
                launchStartProgress,
                launchDurationProgress,
                trailWidth,
                radius
        );
    }

    private record SkillEntry(
            String name,
            int baseDamage,
            int cooldownTurns,
            int failEnergyCost,
            int successEnergyCost,
            SkillEffect.AreaType areaType,
            int areaRadius,
            SkillVisualStyle style,
            String imagePath,
            Integer imageSize
    ) {
        private SkillTemplate toTemplate() {
            if ("Frost Nova".equalsIgnoreCase(name)) {
                return new SkillTemplate(
                        name,
                        baseDamage,
                        cooldownTurns,
                        1.0,
                        failEnergyCost,
                        successEnergyCost,
                        0,
                        patternEffectOrFallback(
                                List.of(
                                        "..X..",
                                        ".XXX.",
                                        "XXCXX",
                                        ".XXX.",
                                        "..X.."
                                ),
                                2,
                                0
                        ),
                        List.of()
                );
            }
            if("Firebolt".equalsIgnoreCase(name)) {
                return new SkillTemplate(
                        name,
                        baseDamage,
                        cooldownTurns,
                        1.0,
                        failEnergyCost,
                        successEnergyCost,
                        0,
                        patternEffectOrFallback(
                                List.of(
                                        "..X..",
                                        ".....",
                                        "X.C.X",
                                        ".....",
                                        "..X.."
                                ),
                                2,
                                0
                        ),
                        List.of()
                );
            }
            return new SkillTemplate(
                    name,
                    baseDamage,
                    cooldownTurns,
                    1.0,
                    failEnergyCost,
                    successEnergyCost,
                    0,
                    new SkillEffect(areaType, areaRadius, 0),
                    List.of()
            );
        }

                private static SkillEffect patternEffectOrFallback(List<String> patternRows, int fallbackRadius, int durationTurns) {
                        try {
                                java.lang.reflect.Method method = SkillEffect.class.getMethod("staticPattern", List.class, int.class);
                                Object effect = method.invoke(null, patternRows, durationTurns);
                                if (effect instanceof SkillEffect skillEffect) {
                                        return skillEffect;
                                }
                        } catch (ReflectiveOperationException ignored) {
                                // Older SkillEffect versions may not expose staticPattern; use radius fallback.
                        }
                        return new SkillEffect(SkillEffect.AreaType.STATIC, fallbackRadius, durationTurns);
                }

        private SkillVisualProfile toVisualProfile() {
            return new SkillVisualProfile(name, style, imagePath, imageSize);
        }
    }
}
