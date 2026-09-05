package dev.dreamveil.prism.api.pack;

/** Optional presentation metadata from prism.json. */
public record PrismPackMetadata(String author, String description, String sourceName, boolean archive) {
    public static final PrismPackMetadata EMPTY = new PrismPackMetadata("", "", "", false);

    public PrismPackMetadata {
        author = author == null ? "" : author;
        description = description == null ? "" : description;
        sourceName = sourceName == null ? "" : sourceName;
    }
}
