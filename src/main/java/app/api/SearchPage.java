package app.api;

import java.util.List;

public record SearchPage(List<ProjectSummary> hits, int offset, int limit, int totalHits) { }
