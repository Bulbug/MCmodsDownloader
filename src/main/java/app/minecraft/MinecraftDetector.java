package app.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a Minecraft folder without changing anything. Version information
 * (including the Java version each release needs) comes from the version
 * JSON files the launcher already downloaded, so nothing is hard-coded.
 */
public final class MinecraftDetector {

    /** Conventional default folder for this operating system. */
    public static Path defaultDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = appData != null ? Path.of(appData) : Path.of(home, "AppData", "Roaming");
            return base.resolve(".minecraft");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "minecraft");
        }
        return Path.of(home, ".minecraft");
    }

    public MinecraftInstallation detect(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return new MinecraftInstallation(directory, false, false, List.of());
        }
        boolean profiles = Files.isRegularFile(directory.resolve("launcher_profiles.json"));
        return new MinecraftInstallation(directory, true, profiles,
                readVersions(directory.resolve("versions")));
    }

    private List<MinecraftVersionInfo> readVersions(Path versionsDir) {
        List<MinecraftVersionInfo> raw = new ArrayList<>();
        if (!Files.isDirectory(versionsDir)) return raw;

        try (DirectoryStream<Path> folders = Files.newDirectoryStream(versionsDir)) {
            for (Path folder : folders) {
                if (!Files.isDirectory(folder)) continue;
                String name = folder.getFileName().toString();
                Path json = folder.resolve(name + ".json");
                if (!Files.isRegularFile(json)) continue;
                MinecraftVersionInfo info = parse(json, name);
                if (info != null) raw.add(info);
            }
        } catch (IOException | SecurityException e) {
            return raw;
        }

        // Second pass: modded versions usually inherit the Java requirement from their parent.
        Map<String, MinecraftVersionInfo> byId = new HashMap<>();
        for (MinecraftVersionInfo v : raw) byId.put(v.id(), v);

        List<MinecraftVersionInfo> resolved = new ArrayList<>();
        for (MinecraftVersionInfo v : raw) {
            int java = v.requiredJava();
            if (java == 0 && v.inheritsFrom() != null) {
                MinecraftVersionInfo parent = byId.get(v.inheritsFrom());
                if (parent != null) java = parent.requiredJava();
            }
            resolved.add(new MinecraftVersionInfo(v.id(), v.type(), v.inheritsFrom(), v.loader(), java));
        }
        resolved.sort(Comparator.comparing(MinecraftVersionInfo::id, String.CASE_INSENSITIVE_ORDER));
        return resolved;
    }

    /** Returns null if the file is not a usable version file. */
    private MinecraftVersionInfo parse(Path jsonFile, String folderName) {
        try {
            String text = Files.readString(jsonFile, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            String id = string(obj, "id", folderName);
            String type = string(obj, "type", "unknown");
            String inherits = string(obj, "inheritsFrom", null);
            int java = 0;
            if (obj.has("javaVersion") && obj.get("javaVersion").isJsonObject()) {
                JsonObject jv = obj.getAsJsonObject("javaVersion");
                if (jv.has("majorVersion")) java = jv.get("majorVersion").getAsInt();
            }
            return new MinecraftVersionInfo(id, type, inherits, detectLoader(id), java);
        } catch (IOException | RuntimeException e) {
            return null; // corrupt or unexpected file: skip this version
        }
    }

    static String detectLoader(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) return "NeoForge";
        if (lower.contains("fabric")) return "Fabric";
        if (lower.contains("quilt")) return "Quilt";
        if (lower.contains("forge")) return "Forge";
        if (lower.contains("optifine")) return "OptiFine";
        return "Vanilla";
    }

    private static String string(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) return obj.get(key).getAsString();
        return fallback;
    }
}
