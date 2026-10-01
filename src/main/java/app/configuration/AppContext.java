package app.configuration;

import app.api.ContentProvider;
import app.downloads.DownloadManager;

/** Shared, long-lived application objects passed to the UI. */
public record AppContext(AppPaths paths, SettingsService settingsService, AppSettings settings,
                         DownloadManager downloads, ContentProvider content) { }
