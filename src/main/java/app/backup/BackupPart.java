package app.backup;

/** The parts of an instance that can be backed up on their own. */
public enum BackupPart {
    MODS("Mods"),
    CONFIG("Config files"),
    SAVES("Worlds"),
    RESOURCE_PACKS("Resource packs"),
    SHADER_PACKS("Shader packs"),
    OPTIONS("Game options");

    private final String label;

    BackupPart(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
