package com.magefight.content.progress;

import com.magefight.content.model.MageArchetype;
import com.magefight.content.model.MageSkillTree;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * Manages promotion flow state (prompt/defer) and promotion target resolution.
 */
public final class ArchetypePromotionService {
    private boolean promptShown;
    private boolean deferred;
    private boolean deferredNotified;

    public ArchetypePromotionService() {
        this.promptShown = false;
        this.deferred = false;
        this.deferredNotified = false;
    }

    public void resetForNewMatch() {
        promptShown = false;
        deferred = false;
        deferredNotified = false;
    }

    public boolean isPromptShown() {
        return promptShown;
    }

    public void markPromptShown() {
        promptShown = true;
    }

    public boolean isDeferred() {
        return deferred;
    }

    public void setDeferred(boolean deferred) {
        this.deferred = deferred;
        if (!deferred) {
            deferredNotified = false;
        }
    }

    public boolean shouldNotifyDeferredOnce() {
        return deferred && !deferredNotified;
    }

    public void markDeferredNotified() {
        deferredNotified = true;
    }

    public Optional<MageArchetype> findNextPromotionTarget(MageProgress progress, MageArchetype currentArchetype) {
        if (progress == null || currentArchetype == null) {
            return Optional.empty();
        }
        return Arrays.stream(MageArchetype.values())
                .filter(candidate -> candidate.tier() > currentArchetype.tier())
                .filter(candidate -> ArchetypeUnlockService.lockReason(candidate, progress).isEmpty())
                .min(Comparator.comparingInt(MageArchetype::tier));
    }

    public boolean isPromotionConditionMet(MageProgress progress, MageSkillTree tree) {
        if (progress == null || tree == null) {
            return false;
        }
        long baseNodes = tree.nodes().stream()
                .filter(node -> node.inspirationCost() > 0 && node.inspirationCost() <= 2)
                .count();
        if (baseNodes == 0) {
            return false;
        }
        long learnedBaseNodes = tree.nodes().stream()
                .filter(node -> node.inspirationCost() > 0 && node.inspirationCost() <= 2)
                .filter(node -> progress.hasLearnedSkill(node.skill().name()))
                .count();
        return learnedBaseNodes * 2 >= baseNodes;
    }
}
