package dev.dreamveil.prism.graph;

import java.util.EnumSet;

/** Standalone core test; intentionally has no Minecraft/Fabric dependency. */
public final class PrismGraphSmokeTest {
    private PrismGraphSmokeTest() {
    }

    public static void main(String[] args) {
        testLifetimeAliasing();
        testPhysicalIncompatibilityPreventsAliasing();
        testReadBeforeProduceRejected();
        testUnusedTransientRejected();
        testAttachmentFormatValidation();
        testExtentResolutionValidation();
        testPackMultipassShape();
        testDepthWriteToSampleHazard();
        testColorWriteReadWriteHazards();
        testMissingSampleUsageRejected();
        System.out.println("Dreamveil Prism core graph smoke tests: PASS");
    }

    private static void testLifetimeAliasing() {
        PrismTextureDesc desc = PrismTextureDesc.colorAttachment(
                PrismTextureExtent.relative(0.5), PrismTextureFormat.RGBA8_UNORM);

        PrismCompiledGraph graph = chain(desc, desc, desc);
        int a = graph.transientPlan().slotFor("a");
        int b = graph.transientPlan().slotFor("b");
        int c = graph.transientPlan().slotFor("c");

        require(a == c, "non-overlapping compatible A/C must alias");
        require(a != b, "overlapping A/B must not alias");
        require(b != c, "overlapping B/C must not alias");
        require(graph.transientPlan().slotCount() == 2, "three logical textures should use two physical slots");
        require(graph.requireLifetime("a").equals(new PrismResourceLifetime(0, 1)), "unexpected A lifetime");
        require(graph.requireLifetime("b").equals(new PrismResourceLifetime(1, 2)), "unexpected B lifetime");
        require(graph.requireLifetime("c").equals(new PrismResourceLifetime(2, 3)), "unexpected C lifetime");
    }

    private static void testPhysicalIncompatibilityPreventsAliasing() {
        PrismTextureDesc rgba8 = PrismTextureDesc.colorAttachment(
                PrismTextureExtent.relative(0.5), PrismTextureFormat.RGBA8_UNORM);
        PrismTextureDesc rgba16f = PrismTextureDesc.colorAttachment(
                PrismTextureExtent.relative(0.5), PrismTextureFormat.RGBA16_FLOAT);

        PrismCompiledGraph graph = chain(rgba8, rgba8, rgba16f);
        require(graph.transientPlan().slotFor("a") != graph.transientPlan().slotFor("c"),
                "different physical texture formats must not alias");
    }

    private static void testReadBeforeProduceRejected() {
        PrismTextureDesc desc = PrismTextureDesc.colorAttachment(PrismTextureFormat.RGBA8_UNORM);
        expectFailure(() -> new PrismRenderGraph()
                .declareResource(PrismResourceDescriptor.transientTexture("scratch", desc))
                .addPass(PrismPass.of("bad", new PrismResourceRef("scratch", PrismResourceAccess.READ)))
                .compile(), "read-before-produce must fail");
    }

    private static void testUnusedTransientRejected() {
        PrismBufferDesc desc = new PrismBufferDesc(
                1024,
                EnumSet.of(PrismBufferUsage.COPY_DST, PrismBufferUsage.COPY_SRC));
        expectFailure(() -> new PrismRenderGraph()
                .declareResource(PrismResourceDescriptor.transientBuffer("unused", desc))
                .compile(), "unused transient must fail");
    }

    private static void testAttachmentFormatValidation() {
        expectFailure(() -> PrismTextureDesc.colorAttachment(
                PrismTextureFormat.D32_FLOAT), "depth format must not be accepted as a color attachment");
        expectFailure(() -> PrismTextureDesc.depthAttachment(
                PrismTextureExtent.relative(1.0),
                PrismTextureFormat.RGBA8_UNORM), "color format must not be accepted as a depth attachment");

        PrismTextureDesc depth = PrismTextureDesc.depthAttachment(
                PrismTextureExtent.relative(1.0),
                PrismTextureFormat.D32_FLOAT);
        require(depth.format().hasDepthAspect(), "depth attachment must preserve depth format");
    }

    private static void testExtentResolutionValidation() {
        PrismTextureExtent half = PrismTextureExtent.relative(0.5);
        require(half.resolveWidth(1920) == 960, "relative width resolution failed");
        require(half.resolveHeight(1080) == 540, "relative height resolution failed");
        require(PrismTextureExtent.relative(0.00001).resolveWidth(1) == 1,
                "relative extent must clamp to at least one pixel");
        expectFailure(() -> PrismTextureExtent.relative(Double.MAX_VALUE).resolveWidth(Integer.MAX_VALUE),
                "overflowing relative extent must fail");
    }

    private static void testPackMultipassShape() {
        PrismTextureDesc intermediate = PrismTextureDesc.colorAttachment(
                PrismTextureExtent.relative(1.0), PrismTextureFormat.RGBA8_UNORM);
        PrismCompiledGraph graph = new PrismRenderGraph()
                .importResource(PrismResourceDescriptor.importedTexture("minecraft.main.color"))
                .declareResource(PrismResourceDescriptor.transientTexture("prism.pack.resource.creator:intermediate", intermediate))
                .addPass(PrismPass.of(
                        "prepare",
                        new PrismResourceRef("minecraft.main.color", PrismResourceAccess.READ),
                        new PrismResourceRef("prism.pack.resource.creator:intermediate", PrismResourceAccess.WRITE)))
                .addPass(PrismPass.of(
                        "composite",
                        new PrismResourceRef("prism.pack.resource.creator:intermediate", PrismResourceAccess.READ),
                        new PrismResourceRef("minecraft.main.color", PrismResourceAccess.READ_WRITE)))
                .compile();
        require(graph.orderedPasses().size() == 2, "multipass pack graph must preserve both passes");
        require(graph.transientPlan().slotCount() == 1, "single pack intermediate must use one physical slot");
    }


    private static void testDepthWriteToSampleHazard() {
        PrismTextureDesc depth = PrismTextureDesc.depthAttachment(
                PrismTextureExtent.relative(1.0),
                PrismTextureFormat.D32_FLOAT);
        PrismCompiledGraph graph = new PrismRenderGraph()
                .declareResource(PrismResourceDescriptor.transientTexture("shadow.depth", depth))
                .addPass(PrismPass.of(
                        "shadow.write",
                        new PrismResourceRef("shadow.depth", PrismResourceAccess.WRITE)))
                .addPass(PrismPass.of(
                        "lighting.sample",
                        new PrismResourceRef("shadow.depth", PrismResourceAccess.READ)))
                .compile();

        PrismResourceTransition transition = graph.transitions().stream()
                .filter(candidate -> candidate.resourceName().equals("shadow.depth")
                        && candidate.toPass().equals("lighting.sample"))
                .findFirst()
                .orElseThrow();
        require(transition.fromState() == PrismResourceUsageState.DEPTH_ATTACHMENT_WRITE,
                "depth producer state must be attachment write");
        require(transition.toState() == PrismResourceUsageState.SAMPLED_READ,
                "depth consumer state must be sampled read");
        require(transition.hazard() == PrismResourceHazard.READ_AFTER_WRITE,
                "depth write->sample must be a RAW hazard");
        require(transition.requiresSynchronization(),
                "depth write->sample transition must require synchronization");
    }

    private static void testColorWriteReadWriteHazards() {
        PrismTextureDesc color = PrismTextureDesc.colorAttachment(
                PrismTextureExtent.relative(1.0), PrismTextureFormat.RGBA8_UNORM);
        PrismCompiledGraph graph = new PrismRenderGraph()
                .declareResource(PrismResourceDescriptor.transientTexture("history", color))
                .addPass(PrismPass.of(
                        "produce",
                        new PrismResourceRef("history", PrismResourceAccess.WRITE)))
                .addPass(PrismPass.of(
                        "sample",
                        new PrismResourceRef("history", PrismResourceAccess.READ)))
                .addPass(PrismPass.of(
                        "rewrite",
                        new PrismResourceRef("history", PrismResourceAccess.WRITE)))
                .compile();

        PrismResourceTransition sample = graph.transitions().stream()
                .filter(candidate -> candidate.toPass().equals("sample"))
                .findFirst()
                .orElseThrow();
        PrismResourceTransition rewrite = graph.transitions().stream()
                .filter(candidate -> candidate.toPass().equals("rewrite"))
                .findFirst()
                .orElseThrow();
        require(sample.fromState() == PrismResourceUsageState.COLOR_ATTACHMENT_WRITE
                        && sample.toState() == PrismResourceUsageState.SAMPLED_READ
                        && sample.hazard() == PrismResourceHazard.READ_AFTER_WRITE,
                "color write->sample hazard plan mismatch");
        require(rewrite.fromState() == PrismResourceUsageState.SAMPLED_READ
                        && rewrite.toState() == PrismResourceUsageState.COLOR_ATTACHMENT_WRITE
                        && rewrite.hazard() == PrismResourceHazard.WRITE_AFTER_READ,
                "color sample->write hazard plan mismatch");
    }

    private static void testMissingSampleUsageRejected() {
        PrismTextureDesc attachmentOnly = new PrismTextureDesc(
                PrismTextureExtent.relative(1.0),
                PrismTextureFormat.RGBA8_UNORM,
                EnumSet.of(PrismTextureUsage.RENDER_ATTACHMENT),
                1);
        expectFailure(() -> new PrismRenderGraph()
                        .declareResource(PrismResourceDescriptor.transientTexture("attachment.only", attachmentOnly))
                        .addPass(PrismPass.of(
                                "produce",
                                new PrismResourceRef("attachment.only", PrismResourceAccess.WRITE)))
                        .addPass(PrismPass.of(
                                "bad.sample",
                                new PrismResourceRef("attachment.only", PrismResourceAccess.READ)))
                        .compile(),
                "sampled read without SAMPLED usage must fail graph compilation");
    }

    private static PrismCompiledGraph chain(
            PrismTextureDesc descA,
            PrismTextureDesc descB,
            PrismTextureDesc descC) {
        return new PrismRenderGraph()
                .importResource(PrismResourceDescriptor.importedTexture("host"))
                .declareResource(PrismResourceDescriptor.transientTexture("a", descA))
                .declareResource(PrismResourceDescriptor.transientTexture("b", descB))
                .declareResource(PrismResourceDescriptor.transientTexture("c", descC))
                .addPass(PrismPass.of(
                        "p0",
                        new PrismResourceRef("host", PrismResourceAccess.READ),
                        new PrismResourceRef("a", PrismResourceAccess.WRITE)))
                .addPass(PrismPass.of(
                        "p1",
                        new PrismResourceRef("a", PrismResourceAccess.READ),
                        new PrismResourceRef("b", PrismResourceAccess.WRITE)))
                .addPass(PrismPass.of(
                        "p2",
                        new PrismResourceRef("b", PrismResourceAccess.READ),
                        new PrismResourceRef("c", PrismResourceAccess.WRITE)))
                .addPass(PrismPass.of(
                        "p3",
                        new PrismResourceRef("c", PrismResourceAccess.READ),
                        new PrismResourceRef("host", PrismResourceAccess.READ_WRITE)))
                .compile();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalStateException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
