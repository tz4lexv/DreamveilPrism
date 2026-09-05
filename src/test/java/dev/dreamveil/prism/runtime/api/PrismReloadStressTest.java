package dev.dreamveil.prism.runtime.api;

/**
 * Standalone hot-reload lifetime stress test.
 *
 * This validates Prism-owned logical lifetime accounting across repeated transactional
 * generations without requiring Minecraft/Fabric or a native GPU backend.
 */
public final class PrismReloadStressTest {
    private static final int RELOADS = 500;
    private static final int PIPELINES_PER_GENERATION = 3;

    private PrismReloadStressTest() {
    }

    public static void main(String[] args) {
        PrismPerformanceApiImpl performance = new PrismPerformanceApiImpl();

        for (int generation = 0; generation < RELOADS; generation++) {
            performance.recordPendingGenerationStarted();
            performance.recordGenerationInstalled(PIPELINES_PER_GENERATION);
            performance.recordSamplerCreated();

            // No real frame/GPU backend exists in this test, so retire immediately after commit.
            performance.recordSamplerClosed();
            performance.recordGenerationRetired(PIPELINES_PER_GENERATION);
            performance.recordPendingGenerationFinished();
        }

        // Simulate a transient pool after all active frame resources have been returned and closed.
        performance.recordTransientPool(64, 64, 64, 64, 32, 32, 4096, 0, 0);
        performance.recordSessionCachedPipelines(0);

        var lifetime = performance.resourceLifetimeSnapshot();
        require(lifetime.generationsInstalled() == RELOADS, "installed generation count mismatch");
        require(lifetime.generationsRetired() == RELOADS, "retired generation count mismatch");
        require(lifetime.pipelineBindingsInstalled() == (long) RELOADS * PIPELINES_PER_GENERATION,
                "installed pipeline binding count mismatch");
        require(lifetime.pipelineBindingsRetired() == (long) RELOADS * PIPELINES_PER_GENERATION,
                "retired pipeline binding count mismatch");
        require(lifetime.samplersCreated() == RELOADS && lifetime.samplersClosed() == RELOADS,
                "sampler lifetime count mismatch");
        require(lifetime.transientTexturesCreated() == lifetime.transientTexturesClosed(),
                "transient texture lifetime mismatch");
        require(lifetime.transientTextureViewsCreated() == lifetime.transientTextureViewsClosed(),
                "transient texture-view lifetime mismatch");
        require(lifetime.transientBuffersCreated() == lifetime.transientBuffersClosed(),
                "transient buffer lifetime mismatch");
        require(lifetime.atLogicalBaseline(), "resource lifetime tracker did not return to baseline");

        System.out.println("Dreamveil Prism 500-generation reload lifetime stress test: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
