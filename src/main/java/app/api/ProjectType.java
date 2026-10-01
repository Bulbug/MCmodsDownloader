package app.api;

import java.util.Locale;

/** Kinds of content on Modrinth. The api value is what Modrinth's search expects in "project_type:". */
public enum ProjectType {
    MOD("mod", "Mods"),
    MODPACK("modpack", "Modpacks"),
    RESOURCE_PACK("resourcepack", "Resource packs"),
    SHADER("shader", "Shaders");

    private final String apiValue;
    private final String label;

    ProjectType(String apiValue, String label) {
        this.apiValue = apiValue;
        this.label = label;
    }

    public String apiValue() {
        return apiValue;
    }

    public String label() {
        return label;
    }

    /** Mods and modpacks are tied to a loader (Fabric, Forge, ...). Packs and shaders are not filtered by it. */
    public boolean usesLoader() {
        return this == MOD || this == MODPACK;
    }

    public static ProjectType fromApi(String value, ProjectType fallback) {
        if (value != null) {
            for (ProjectType t : values()) {
                if (t.apiValue.equals(value.toLowerCase(Locale.ROOT))) return t;
            }
        }
        return fallback;
    }
}
