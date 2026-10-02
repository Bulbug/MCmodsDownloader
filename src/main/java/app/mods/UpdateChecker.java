package app.mods;

import app.api.ContentProvider;
import app.api.ProjectVersion;
import app.api.ProviderException;
import app.downloads.Checksum;
import app.instance.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds newer versions of the mods this app installed, with one request for the whole instance.
 * It hashes the files that are really on disk (not the saved record), so a replaced or edited file
 * is noticed. Call from a background thread: hashing and the network request take time.
 */
public final class UpdateChecker {

    private static final Logger log = LoggerFactory.getLogger("Mods");

    /** A mod that has a newer version available. */
    public record Candidate(InstalledMod installed, ProjectVersion latest) { }

    /**
     * @param updates    mods with a newer version
     * @param upToDate   mods already on the newest version
     * @param notChecked mods that could not be checked (file missing, unknown to the platform for this
     *                   Minecraft version and loader, or the file does not match its record)
     */
    public record Report(List<Candidate> updates, List<InstalledMod> upToDate, List<InstalledMod> notChecked) { }

    private final ContentProvider provider;

    public UpdateChecker(ContentProvider provider) {
        this.provider = provider;
    }

    public Report check(Path instanceDir, Instance instance, boolean allowPreRelease) {
        List<Candidate> updates = new ArrayList<>();
        List<InstalledMod> upToDate = new ArrayList<>();
        List<InstalledMod> notChecked = new ArrayList<>();

        if (instance.loader().equalsIgnoreCase("vanilla")) return new Report(updates, upToDate, notChecked);

        Path modsDir = instanceDir.resolve("game").resolve("mods");
        Map<String, InstalledMod> byHash = new LinkedHashMap<>();
        for (InstalledMod m : new InstalledContentStore(instanceDir).load()) {
            Path file = ModManager.safeResolve(modsDir, m.fileName());
            if (file == null || !Files.isRegularFile(file)) {
                notChecked.add(m);
                continue;
            }
            try {
                String hash = Checksum.compute(Checksum.Algorithm.SHA512, file);
                if (byHash.putIfAbsent(hash, m) != null) notChecked.add(m); // identical copy of another file
            } catch (IOException e) {
                log.warn("Could not read {} for the update check", file, e);
                notChecked.add(m);
            }
        }

        Map<String, ProjectVersion> latest = provider.latestVersionsForHashes(
                byHash.keySet(), instance.minecraftVersion(), instance.loader(), !allowPreRelease);

        for (Map.Entry<String, InstalledMod> entry : byHash.entrySet()) {
            String hash = entry.getKey();
            InstalledMod installed = entry.getValue();
            ProjectVersion newest = latest.get(hash);

            if (newest == null || (newest.projectId() != null && !newest.projectId().equals(installed.projectId()))) {
                notChecked.add(installed);
            } else if (newest.id().equals(installed.versionId()) || hasFile(newest, hash)
                    || !isNewer(installed, newest)) {
                upToDate.add(installed);
            } else {
                updates.add(new Candidate(installed, newest));
            }
        }
        updates.sort((a, b) -> a.installed().title().compareToIgnoreCase(b.installed().title()));
        return new Report(updates, upToDate, notChecked);
    }

    private static boolean hasFile(ProjectVersion v, String sha512) {
        return v.files().stream().anyMatch(f -> sha512.equalsIgnoreCase(f.sha512()));
    }

    /**
     * Never offer a downgrade: when the user runs a beta that is newer than the latest release,
     * the platform's "latest release" is older than what they have.
     */
    private boolean isNewer(InstalledMod installed, ProjectVersion candidate) {
        String installedDate = installed.publishedAt();
        if (installedDate == null) {
            try {
                installedDate = provider.version(installed.versionId()).datePublished();
            } catch (ProviderException e) {
                log.warn("Could not look up the publish date of {}: {}", installed.title(), e.getMessage());
            }
        }
        if (installedDate == null || candidate.datePublished() == null) return true; // unknown: assume newer
        // ISO-8601 timestamps compare correctly as text.
        return candidate.datePublished().toLowerCase(Locale.ROOT).compareTo(installedDate.toLowerCase(Locale.ROOT)) > 0;
    }
}
