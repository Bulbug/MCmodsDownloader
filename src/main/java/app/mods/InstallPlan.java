package app.mods;

import app.instance.Instance;

import java.util.List;

/**
 * What installing a mod would do. Nothing has been changed when you hold one of these.
 * If {@code problems} is not empty the plan must not be executed.
 */
public record InstallPlan(Instance instance, List<PlannedMod> toInstall, List<String> alreadyInstalled,
                          List<String> optional, List<String> warnings, List<String> problems) {

    public boolean canInstall() {
        return problems.isEmpty() && !toInstall.isEmpty();
    }
}
