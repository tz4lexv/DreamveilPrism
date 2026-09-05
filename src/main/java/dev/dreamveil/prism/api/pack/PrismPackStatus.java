package dev.dreamveil.prism.api.pack;

/** Runtime state for a discovered Prism shader pack. */
public enum PrismPackStatus {
    DISCOVERED,
    READY,
    STALE,
    ERROR,
    DISABLED
}
