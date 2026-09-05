package dev.dreamveil.prism.temporal;

import java.util.HashMap;
import java.util.Map;

/** Bounded consecutive-frame history. Stores final clip positions, not mutable model poses. */
public final class PrismVertexMotionHistory<K> {
    public record Sample(float[] clips, long topology) {}
    private Map<K, Sample> previous = new HashMap<>();
    private Map<K, Sample> current = new HashMap<>();
    private final int maxVertices;
    private int vertices;
    public PrismVertexMotionHistory(int maxVertices) {
        if (maxVertices < 1) throw new IllegalArgumentException("maxVertices");
        this.maxVertices = maxVertices;
    }
    public void beginFrame(boolean valid) {
        Map<K, Sample> old = previous;
        previous = current;
        current = old;
        current.clear();
        if (!valid) previous.clear();
        vertices = 0;
    }
    /** Ownership of clips transfers to the history. Null means reject accumulation for this draw. */
    public Sample record(K key, float[] clips, long topology) {
        if (clips.length % 4 != 0) throw new IllegalArgumentException("clip positions must be vec4");
        if (current.containsKey(key)) return null;
        int count = clips.length / 4;
        if (count > maxVertices - vertices) return null;
        vertices += count;
        Sample old = previous.get(key);
        current.put(key, new Sample(clips, topology));
        return old != null && old.clips.length == clips.length && old.topology == topology ? old : null;
    }
    public void clear() { previous.clear(); current.clear(); vertices = 0; }
}
