package dev.dreamveil.prism.pack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.runtime.api.PrismPerformanceApiImpl;

/** Triple-buffered non-blocking GPU timestamps using Minecraft's backend-neutral GpuQueryPool. */
final class PrismGpuProfiler implements AutoCloseable {
    private static final int RING = 4;
    private static final int QUERIES_PER_PASS = RING * 2;

    private final Map<String, PassState> passes = new LinkedHashMap<>();
    private GpuDevice device;
    private GpuQueryPool pool;
    private PrismPerformanceApiImpl sink;
    private float timestampPeriod = 1.0f;

    void configure(GpuDevice device, List<String> passNames, PrismPerformanceApiImpl sink) {
        if (this.device != device || pool == null || passes.size() != passNames.size() || !passes.keySet().equals(new java.util.LinkedHashSet<>(passNames))) {
            closePool();
            this.device = device;
            this.sink = sink;
            this.timestampPeriod = Math.max(0.000001f, device.getDeviceInfo().timestampPeriod());
            if (passNames.isEmpty()) return;
            try {
                pool = device.createTimestampQueryPool(passNames.size() * QUERIES_PER_PASS);
                int offset = 0;
                for (String pass : passNames) {
                    passes.put(pass, new PassState(offset));
                    offset += QUERIES_PER_PASS;
                }
            } catch (RuntimeException exception) {
                PrismMod.LOGGER.warn("Prism GPU timestamp profiler is unavailable on this backend", exception);
                closePool();
            }
        } else {
            this.sink = sink;
            // Do not attribute delayed queries from a previous hot-reload generation to the new snapshot.
            passes.values().forEach(state -> state.sequence = 0L);
        }
    }

    void profileDraw(String passName, RenderPass renderPass, Runnable draw) {
        PassState state = passes.get(passName);
        if (pool == null || state == null) {
            draw.run();
            return;
        }
        int slot = (int)(state.sequence % RING);
        int begin = state.base + slot * 2;
        int end = begin + 1;
        if (state.sequence >= RING) poll(passName, begin, end);
        try {
            try {
                renderPass.writeTimestamp(pool, begin);
            } catch (RuntimeException timestampFailure) {
                // Profiling is optional. A timestamp failure must not swallow or duplicate a real draw failure.
                PrismMod.LOGGER.debug("Prism GPU timestamp begin write failed for {}", passName, timestampFailure);
                draw.run();
                return;
            }

            // Rendering failures are intentionally allowed to propagate to Prism's transactional pack error path.
            draw.run();

            try {
                renderPass.writeTimestamp(pool, end);
            } catch (RuntimeException timestampFailure) {
                PrismMod.LOGGER.debug("Prism GPU timestamp end write failed for {}", passName, timestampFailure);
            }
        } finally {
            state.sequence++;
        }
    }

    private void poll(String passName, int begin, int end) {
        try {
            OptionalLong a = pool.getValue(begin);
            OptionalLong b = pool.getValue(end);
            if (a.isPresent() && b.isPresent() && b.getAsLong() >= a.getAsLong() && sink != null) {
                double nanos = (b.getAsLong() - a.getAsLong()) * (double) timestampPeriod;
                if (Double.isFinite(nanos) && nanos >= 0.0 && nanos <= Long.MAX_VALUE) sink.recordGpuPass(passName, Math.round(nanos));
            }
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Prism GPU timestamp query read failed for {}", passName, exception);
        }
    }

    void deactivate() {
        sink = null;
        passes.values().forEach(state -> state.sequence = 0L);
    }

    private void closePool() {
        passes.clear();
        if (pool != null) {
            try { pool.close(); } catch (RuntimeException exception) { PrismMod.LOGGER.debug("GPU query pool cleanup failed", exception); }
            pool = null;
        }
    }

    @Override public void close() {
        closePool();
        device = null;
        sink = null;
    }

    private static final class PassState {
        final int base;
        long sequence;
        PassState(int base) { this.base = base; }
    }
}
