package dev.dreamveil.prism.pack;

import java.util.UUID;

/** Extraction-owned identity; never retain an Entity or a live world in motion history. */
public interface PrismMotionIdentity {
    UUID prism$motionId();
    void prism$motionId(UUID id);
}
