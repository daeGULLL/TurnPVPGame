package com.magefight.content.progress;

import com.magefight.content.model.MageArchetype;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 아키타입 언락 조건을 관리합니다.
 *
 * 아키타입은 한 번만 선택 가능하며, 선택 이후에는 변경할 수 없습니다.
 * 선택한 아키타입의 위계보다 낮거나 같은 다른 아키타입은 더 이상 선택할 수 없습니다.
 *
 * wins는 별도로 기록되지만 아키타입 선택 조건으로는 사용되지 않습니다.
 */
public final class ArchetypeUnlockService {
    private ArchetypeUnlockService() {
    }

    public static Optional<String> lockReason(MageArchetype archetype, MageProgress progress) {
        MageArchetype selected = progress.selectedArchetype();
        if (selected != null) {
            if (selected == archetype) {
                return Optional.of("이미 선택한 호칭: " + selected.displayName());
            }
            if (selected.tier() >= archetype.tier()) {
                return Optional.of("이미 더 높은 위계의 호칭을 선택함: " + selected.displayName());
            }
        }

        int currentLevel = progress.level();
        int requiredLevel = archetype.tier();
        if (currentLevel < requiredLevel) {
            return Optional.of("캐릭터 레벨 부족: " + currentLevel + "/" + requiredLevel);
        }

        return Optional.empty();
    }

    public static boolean trySelect(MageArchetype archetype, MageProgress progress) {
        Optional<String> reason = lockReason(archetype, progress);
        if (reason.isPresent()) {
            return false;
        }
        progress.selectArchetype(archetype);
        return true;
    }

    public static List<MageArchetype> unlockedByTier(MageProgress progress) {
        List<MageArchetype> result = new ArrayList<>();
        if (progress.selectedArchetype() != null) {
            result.add(progress.selectedArchetype());
        }
        result.sort(Comparator.comparingInt(MageArchetype::tier));
        return result;
    }

    public static List<MageArchetype> autoUnlockEligible(MageProgress progress) {
        List<MageArchetype> selectable = new ArrayList<>();
        if (progress.hasSelectedArchetype()) {
            return selectable;
        }
        for (MageArchetype archetype : MageArchetype.values()) {
            if (lockReason(archetype, progress).isEmpty()) {
                selectable.add(archetype);
            }
        }
        selectable.sort(Comparator.comparingInt(MageArchetype::tier));
        return selectable;
    }
}