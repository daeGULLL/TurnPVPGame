package com.magefight.content.factory;

import com.magefight.content.factory.character.MageCharacterFactoryProvider;
import com.magefight.content.factory.skill.MageSkillTreeFactory;
import com.magefight.content.model.FighterSpec;
import com.magefight.content.model.MageArchetype;
import com.magefight.content.model.MageSkillTree;
import com.magefight.content.progress.MageProgress;
import com.turngame.domain.character.GameCharacter;
import com.turngame.domain.enums.CharacterType;
import com.turngame.domain.map.BattleMap;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.factory.character.CharacterFactoryProvider;
import com.turngame.factory.map.MapFactoryProvider;
import com.turngame.factory.skill.SkillFactoryProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GamePresetFactory {
    public FighterSpec createPlayerSpec() {
        return createPlayerSpec(MageArchetype.APPRENTICE);
    }

    public FighterSpec createPlayerSpec(MageArchetype archetype) {
        return createPlayerSpec(archetype, new MageProgress(0, archetype == null ? MageArchetype.APPRENTICE : archetype, new HashMap<>()));
    }

    public FighterSpec createPlayerSpec(MageProgress progress) {
        MageArchetype archetype = progress == null || progress.selectedArchetype() == null
                ? MageArchetype.APPRENTICE
                : progress.selectedArchetype();
        return createPlayerSpec(archetype, progress == null ? MageProgress.starter() : progress);
    }

    public FighterSpec createPlayerSpec(MageArchetype archetype, MageProgress progress) {
        GameCharacter baseCharacter = MageCharacterFactoryProvider.byArchetype(archetype).createCharacter("p-1");
        List<SkillTemplate> baseSkills = SkillFactoryProvider.byCharacterType(baseCharacter.type()).createStarterSkills();
        MageSkillTree tree = MageSkillTreeFactory.create(archetype);
        List<SkillTemplate> learnedTreeSkills = progress == null ? List.of() : tree.learnedSkills(progress);
        List<SkillTemplate> combined = mergeSkills(baseSkills, learnedTreeSkills);
        return new FighterSpec(baseCharacter, combined);
    }

    public FighterSpec createBotSpec() {
        return createBotSpec(MageArchetype.ELEMENTALIST);
    }

    public FighterSpec createBotSpec(MageArchetype archetype) {
        if (archetype == null) {
            archetype = MageArchetype.ELEMENTALIST;
        }
        if (archetype == MageArchetype.APPRENTICE) {
            archetype = MageArchetype.RUNE_SCHOLAR;
        }
        GameCharacter baseCharacter = MageCharacterFactoryProvider.byArchetype(archetype).createCharacter("p-2");
        List<SkillTemplate> baseSkills = SkillFactoryProvider.byCharacterType(baseCharacter.type()).createStarterSkills();
        return new FighterSpec(baseCharacter, baseSkills);
    }

    public MageSkillTree createMageSkillTree(MageArchetype archetype) {
        return MageSkillTreeFactory.create(archetype == null ? MageArchetype.APPRENTICE : archetype);
    }

    public FighterSpec createBotEngineMageSpec() {
        GameCharacter baseCharacter = CharacterFactoryProvider.byPreference(CharacterType.MAGE.name()).createCharacter("p-2");
        List<SkillTemplate> baseSkills = SkillFactoryProvider.byCharacterType(baseCharacter.type()).createStarterSkills();
        return new FighterSpec(baseCharacter, baseSkills);
    }

    public BattleMap createMap() {
        return MapFactoryProvider.randomFactory().createMap();
    }

    public BattleMap createMap(String mapName) { return MapFactoryProvider.getMap(mapName).createMap(); }

    private List<SkillTemplate> mergeSkills(List<SkillTemplate> baseSkills, List<SkillTemplate> learnedTreeSkills) {
        Map<String, SkillTemplate> merged = new LinkedHashMap<>();
        for (SkillTemplate skill : baseSkills) {
            merged.putIfAbsent(skill.name(), skill);
        }
        for (SkillTemplate skill : learnedTreeSkills) {
            merged.putIfAbsent(skill.name(), skill);
        }
        return new ArrayList<>(merged.values());
    }
}