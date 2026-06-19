package com.magefight.content.factory.skill;

import com.magefight.content.model.SkillVisualProfile;
import java.util.List;

public final class MageSkillVisualFactory {
    private MageSkillVisualFactory() {
    }

    public static List<SkillVisualProfile> createAll() {
        return List.copyOf(MageSkillCatalog.visualProfiles());
    }
}
