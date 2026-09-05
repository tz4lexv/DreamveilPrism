package dev.dreamveil.prism.pack;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.blaze3d.GpuFormat;
import java.util.List;
import com.mojang.blaze3d.systems.GpuDevice;

/** Bounded session cache for already precompiled, content-addressed Prism pipelines. */
final class PrismPipelineSessionCache {
    private static final int MAX_ENTRIES = 128;

    private final LinkedHashMap<Key, PrismCompiledFullscreenPipeline> entries =
            new LinkedHashMap<>(32, 0.75f, true);
    private GpuDevice device;

    PrismCompiledFullscreenPipeline find(
            GpuDevice candidateDevice,
            List<GpuFormat> outputFormats,
            String packId,
            String pipelineId,
            long sourceFingerprint) {
        ensureDevice(candidateDevice);
        return entries.get(new Key(List.copyOf(outputFormats), packId, pipelineId, sourceFingerprint));
    }

    void put(
            GpuDevice candidateDevice,
            List<GpuFormat> outputFormats,
            String packId,
            PrismCompiledFullscreenPipeline pipeline) {
        ensureDevice(candidateDevice);
        entries.put(
                new Key(List.copyOf(outputFormats), packId, pipeline.pipelineId(), pipeline.sourceFingerprint()),
                pipeline);
        while (entries.size() > MAX_ENTRIES) {
            Map.Entry<Key, PrismCompiledFullscreenPipeline> eldest = entries.entrySet().iterator().next();
            entries.remove(eldest.getKey());
        }
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.clear();
        device = null;
    }

    private void ensureDevice(GpuDevice candidate) {
        if (device == candidate) {
            return;
        }
        entries.clear();
        device = candidate;
    }

    private record Key(
            List<GpuFormat> outputFormats,
            String packId,
            String pipelineId,
            long sourceFingerprint) {
    }
}
