package app.mods;

import java.util.List;

/**
 * What removing one mod would affect. Nothing has been changed when you hold one of these.
 *
 * @param dependents         installed mods that need the target, directly or through other mods
 *                           (they may stop working if it is removed)
 * @param unusedDependencies mods that came in only as dependencies and that nothing else needs
 *                           once the target is gone (can optionally be removed too)
 */
public record RemovalPlan(ManagedMod target, List<InstalledMod> dependents, List<InstalledMod> unusedDependencies) {

    public boolean hasDependents() {
        return !dependents.isEmpty();
    }
}
