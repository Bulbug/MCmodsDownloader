package app.minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds installed Java runtimes by reading files. It never launches java.exe,
 * so scanning is fast and cannot run anything untrusted.
 */
public final class JavaDetector {

    private static final int MAX_DEPTH = 4;

    private final List<Path> directHomes;
    private final List<Path> searchRoots;

    /**
     * @param directHomes folders that are themselves a Java home (JAVA_HOME, PATH entries)
     * @param searchRoots folders that contain Java homes somewhere below them
     */
    public JavaDetector(List<Path> directHomes, List<Path> searchRoots) {
        this.directHomes = List.copyOf(directHomes);
        this.searchRoots = List.copyOf(searchRoots);
    }

    /** Detector configured with the usual places on the current computer. */
    public static JavaDetector forThisMachine(Path minecraftDir) {
        List<Path> direct = new ArrayList<>();
        addIfValid(direct, System.getenv("JAVA_HOME"));

        String pathVar = System.getenv("PATH");
        if (pathVar != null) {
            for (String entry : pathVar.split(File.pathSeparator)) {
                Path p = toPath(entry);
                if (p != null && p.getFileName() != null
                        && p.getFileName().toString().equalsIgnoreCase("bin")
                        && p.getParent() != null) {
                    direct.add(p.getParent());
                }
            }
        }

        List<Path> roots = new ArrayList<>();
        for (String envName : new String[]{"ProgramFiles", "ProgramW6432", "ProgramFiles(x86)"}) {
            String base = System.getenv(envName);
            if (base == null) continue;
            for (String vendorDir : new String[]{"Java", "Eclipse Adoptium", "Microsoft", "Zulu",
                    "Amazon Corretto", "BellSoft", "Semeru", "Eclipse Foundation"}) {
                addIfValid(roots, Path.of(base, vendorDir).toString());
            }
        }
        addIfValid(roots, "/usr/lib/jvm");
        addIfValid(roots, "/Library/Java/JavaVirtualMachines");
        if (minecraftDir != null) {
            // Runtimes downloaded by the official launcher live here.
            roots.add(minecraftDir.resolve("runtime"));
        }
        return new JavaDetector(direct, roots);
    }

    /** Returns every distinct Java found, newest major version first. */
    public List<JavaInstall> detect() {
        Map<Path, JavaInstall> found = new LinkedHashMap<>();

        for (Path home : directHomes) {
            if (isJavaHome(home)) {
                read(home, "Environment").ifPresent(i -> found.putIfAbsent(canonical(home), i));
            }
        }
        for (Path root : searchRoots) {
            search(root, 0, found);
        }

        List<JavaInstall> result = new ArrayList<>(found.values());
        result.sort(Comparator.comparingInt(JavaInstall::major).reversed()
                .thenComparing(i -> i.home().toString()));
        return result;
    }

    private void search(Path dir, int depth, Map<Path, JavaInstall> found) {
        if (depth > MAX_DEPTH || !Files.isDirectory(dir)) return;
        if (isJavaHome(dir)) {
            read(dir, "Installed").ifPresent(i -> found.putIfAbsent(canonical(dir), i));
            return; // do not look inside a Java home
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) search(child, depth + 1, found);
            }
        } catch (IOException | SecurityException ignored) {
            // Unreadable folder: skip it.
        }
    }

    static boolean isJavaHome(Path dir) {
        return Files.isRegularFile(dir.resolve("bin").resolve("java.exe"))
                || Files.isRegularFile(dir.resolve("bin").resolve("java"));
    }

    static Optional<JavaInstall> read(Path home, String source) {
        Map<String, String> release = readRelease(home.resolve("release"));
        String version = release.getOrDefault("JAVA_VERSION", "unknown");
        String vendor = release.getOrDefault("IMPLEMENTOR", "unknown");
        return Optional.of(new JavaInstall(home, parseMajor(version), version, vendor, source));
    }

    /** "21.0.5" -> 21, "1.8.0_392" -> 8, "17" -> 17, "22-ea" -> 22, unknown -> 0. */
    static int parseMajor(String version) {
        if (version == null) return 0;
        String v = version.trim();
        if (v.startsWith("1.")) v = v.substring(2);
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(v.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> readRelease(Path file) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(file)) return values;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable release file: treat as unknown version.
        }
        return values;
    }

    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static void addIfValid(List<Path> list, String raw) {
        Path p = toPath(raw);
        if (p != null) list.add(p);
    }

    private static Path toPath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Path.of(raw.trim());
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
