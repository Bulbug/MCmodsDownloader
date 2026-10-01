package app.ui;

/** Sidebar destinations, with the development phase that will implement each. */
public enum Page {
    HOME("Home", "Overview of your instances and activity.", 1),
    MODS("Mods", "Search, install, update, and remove mods.", 5),
    MODPACKS("Modpacks", "Import and install modpacks.", 7),
    DATAPACKS("Datapacks", "Manage datapacks per world.", 8),
    RESOURCE_PACKS("Resource Packs", "Manage resource packs.", 8),
    SHADERS("Shaders", "Manage shader packs.", 8),
    INSTANCES("Instances", "Create and manage isolated instances.", 3),
    DOWNLOADS("Downloads", "Queue, progress, and history.", 4),
    SETTINGS("Settings", "Directories, Java, memory, and theme.", 1);

    private final String title;
    private final String description;
    private final int phase;

    Page(String title, String description, int phase) {
        this.title = title;
        this.description = description;
        this.phase = phase;
    }

    public String title()       { return title; }
    public String description() { return description; }
    public int phase()          { return phase; }
}
