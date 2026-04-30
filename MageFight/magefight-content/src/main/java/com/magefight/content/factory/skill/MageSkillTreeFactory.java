package com.magefight.content.factory.skill;

import com.magefight.content.model.MageArchetype;
import com.magefight.content.model.MageSkillTree;
import com.magefight.content.model.SkillTreeBranch;
import com.magefight.content.model.SkillTreeLink;
import com.magefight.content.model.SkillTreeNode;
import com.turngame.domain.enums.CharacterType;
import com.turngame.domain.skill.SkillEffect;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.factory.skill.SkillFactoryProvider;

import java.util.ArrayList;
import java.util.List;

public final class MageSkillTreeFactory {
    private MageSkillTreeFactory() {
    }

    public static MageSkillTree create(MageArchetype archetype) {
        if (archetype == MageArchetype.ELEMENTALIST) {
            SkillTreeBranch flameBranch = new SkillTreeBranch(
                    "flame",
                    "Flame Branch",
                    List.of(
                            new SkillTreeNode("flame", skill("Firebolt", 8, 1, SkillEffect.AreaType.STATIC, 1), 1, 1, List.of()),
                            new SkillTreeNode("flame", skill("Frost Nova", 12, 2, SkillEffect.AreaType.STATIC, 2), 2, 2, List.of("Firebolt"))
                    )
            );
            SkillTreeBranch mobilityBranch = new SkillTreeBranch(
                    "mobility",
                    "Mobility Branch",
                    List.of(
                            new SkillTreeNode("mobility", skill("Blink Cut", 16, 3, SkillEffect.AreaType.DYNAMIC, 2), 2, 3, List.of("Frost Nova"))
                    )
            );
            return new MageSkillTree(
                    archetype,
                    List.of(flameBranch, mobilityBranch),
                    List.of(new SkillTreeLink("flame", "Frost Nova", "mobility", "Blink Cut"))
            );
        }

        if (archetype == MageArchetype.RUNE_SCHOLAR) {
            SkillTreeBranch runeBranch = new SkillTreeBranch(
                    "rune",
                    "Rune Branch",
                    List.of(
                            new SkillTreeNode("rune", skill("Arcane Sigil", 7, 1, SkillEffect.AreaType.STATIC, 1), 1, 1, List.of()),
                            new SkillTreeNode("rune", skill("Rune Ward", 11, 2, SkillEffect.AreaType.STATIC, 2), 2, 2, List.of("Arcane Sigil"))
                    )
            );
            SkillTreeBranch burstBranch = new SkillTreeBranch(
                    "burst",
                    "Burst Branch",
                    List.of(
                            new SkillTreeNode("burst", skill("Mana Burst", 15, 3, SkillEffect.AreaType.DYNAMIC, 3), 2, 3, List.of("Rune Ward"))
                    )
            );
            return new MageSkillTree(
                    archetype,
                    List.of(runeBranch, burstBranch),
                    List.of(new SkillTreeLink("rune", "Rune Ward", "burst", "Mana Burst"))
            );
        }

        List<SkillTemplate> starterSkills = SkillFactoryProvider
                .byCharacterType(CharacterType.MAGE)
                .createStarterSkills();
        List<SkillTemplate> starterPool = starterSkills.isEmpty()
                ? List.of(skill("마나 방출", 6, 0))
                : starterSkills;
        SkillTemplate baseStarter = starterPool.get(0);

        List<SkillTreeNode> coreNodes = new ArrayList<>();
        for (SkillTemplate starter : starterPool) {
            coreNodes.add(new SkillTreeNode("core", starter, 0, 1, List.of()));
        }
        SkillTreeBranch coreBranch = new SkillTreeBranch("core", "Core Branch", coreNodes);
        SkillTreeBranch advanceBranch = new SkillTreeBranch(
                "advance",
                "Advance Branch",
                List.of(
                        new SkillTreeNode("advance", skill("Firebolt", 10, 1, SkillEffect.AreaType.STATIC, 1), 1, 1, List.of(baseStarter.name())),
                        new SkillTreeNode("advance", skill("Blink Cut", 14, 2, SkillEffect.AreaType.DYNAMIC, 2), 2, 2, List.of("Firebolt")),
                        new SkillTreeNode("advance", skill("Arcane Sigil", 12, 2, SkillEffect.AreaType.STATIC, 1), 2, 2, List.of("Firebolt")),
                        new SkillTreeNode("advance", skill("Mana Burst", 18, 3, SkillEffect.AreaType.DYNAMIC, 3), 3, 3, List.of("Blink Cut", "Arcane Sigil"))
                )
        );

        return new MageSkillTree(
                archetype,
                List.of(coreBranch, advanceBranch),
                List.of(
                        new SkillTreeLink("core", baseStarter.name(), "advance", "Firebolt"),
                        new SkillTreeLink("advance", "Firebolt", "advance", "Blink Cut"),
                        new SkillTreeLink("advance", "Firebolt", "advance", "Arcane Sigil"),
                        new SkillTreeLink("advance", "Blink Cut", "advance", "Mana Burst"),
                        new SkillTreeLink("advance", "Arcane Sigil", "advance", "Mana Burst")
                )
        );
    }

    private static SkillTemplate skill(String name, int baseDamage, int cooldownTurns) {
        return skill(name, baseDamage, cooldownTurns, 1, 1, SkillEffect.AreaType.STATIC, 1);
    }

    private static SkillTemplate skill(String name, int baseDamage, int cooldownTurns, SkillEffect.AreaType areaType, int areaRadius) {
        return skill(name, baseDamage, cooldownTurns, 1, 1, areaType, areaRadius);
    }

    private static SkillTemplate skill(String name, int baseDamage, int cooldownTurns, int failEnergyCost, int successEnergyCost, SkillEffect.AreaType areaType, int areaRadius) {
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
}