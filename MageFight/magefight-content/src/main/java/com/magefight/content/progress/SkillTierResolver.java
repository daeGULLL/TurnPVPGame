package com.magefight.content.progress;

import com.magefight.content.factory.skill.MageSkillTreeFactory;
import com.magefight.content.model.MageArchetype;
import com.magefight.content.model.MageSkillTree;
import com.magefight.content.model.SkillTreeNode;

import java.util.HashMap;
import java.util.Map;

/**
 * 스킬 이름으로부터 해당 스킬의 계층(Tier)을 조회합니다.
 */
public final class SkillTierResolver {
    private static final Map<String, Integer> SKILL_TIER_CACHE = new HashMap<>();
    private static boolean initialized = false;

    private SkillTierResolver() {
    }

    public static int getTierForSkill(String skillName) {
        if (!initialized) {
            initializeCache();
        }
        return SKILL_TIER_CACHE.getOrDefault(skillName, -1);
    }

    public static Map<String, Integer> buildSkillTierMap(Iterable<String> skillNames) {
        Map<String, Integer> tierMap = new HashMap<>();
        for (String skillName : skillNames) {
            int tier = getTierForSkill(skillName);
            if (tier >= 1 && tier <= 3) {
                tierMap.put(skillName, tier);
            }
        }
        return tierMap;
    }

    private static void initializeCache() {
        synchronized (SKILL_TIER_CACHE) {
            if (initialized) {
                return;
            }

            for (MageArchetype archetype : MageArchetype.values()) {
                MageSkillTree tree = MageSkillTreeFactory.create(archetype);
                int tier = archetype.tier();

                for (SkillTreeNode node : tree.nodes()) {
                    SKILL_TIER_CACHE.putIfAbsent(node.skill().name(), tier);
                }
            }

            initialized = true;
        }
    }
}