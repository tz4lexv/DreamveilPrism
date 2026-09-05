package dev.dreamveil.prism.pack;

import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;

import java.util.EnumSet;
import java.util.List;

import dev.dreamveil.prism.graph.PrismBufferDesc;
import dev.dreamveil.prism.graph.PrismBufferUsage;
import dev.dreamveil.prism.graph.PrismCompiledGraph;
import dev.dreamveil.prism.graph.PrismPass;
import dev.dreamveil.prism.graph.PrismPassExecutionType;
import dev.dreamveil.prism.graph.PrismRenderGraph;
import dev.dreamveil.prism.graph.PrismResourceAccess;
import dev.dreamveil.prism.graph.PrismResourceDescriptor;
import dev.dreamveil.prism.graph.PrismResourceHazard;
import dev.dreamveil.prism.graph.PrismResourceRef;
import dev.dreamveil.prism.graph.PrismResourceTransition;
import dev.dreamveil.prism.graph.PrismResourceType;
import dev.dreamveil.prism.graph.PrismResourceUsageState;

/** Pure Synchronization2 planning checks; no device or Minecraft client is required. */
public final class PrismVulkanBarrierPlannerSmokeTest {
    private PrismVulkanBarrierPlannerSmokeTest() {}

    public static void main(String[] args) {
        require(PrismVulkanBarrierPlanner.stageMask(
                        PrismResourceUsageState.COLOR_ATTACHMENT_WRITE,
                        PrismPassExecutionType.GRAPHICS)
                        == VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                "color writes must synchronize at color-attachment output");
        require(PrismVulkanBarrierPlanner.accessMask(
                        PrismResourceUsageState.COLOR_ATTACHMENT_WRITE)
                        == VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                "color writes need the color-attachment write access bit");
        require(PrismVulkanBarrierPlanner.stageMask(
                        PrismResourceUsageState.SAMPLED_READ,
                        PrismPassExecutionType.GRAPHICS)
                        == VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                "graphics samples must synchronize at fragment shading");
        require(PrismVulkanBarrierPlanner.accessMask(
                        PrismResourceUsageState.SAMPLED_READ)
                        == VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                "sampled reads need the sampled-read access bit");
        require(PrismVulkanBarrierPlanner.stageMask(
                        PrismResourceUsageState.STORAGE_IMAGE_WRITE,
                        PrismPassExecutionType.COMPUTE)
                        == VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                "compute storage writes must synchronize at compute shading");
        require(PrismVulkanBarrierPlanner.accessMask(
                        PrismResourceUsageState.STORAGE_IMAGE_WRITE)
                        == VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                "storage writes need the shader-storage write access bit");
        require(PrismVulkanBarrierPlanner.stageMask(
                        PrismResourceUsageState.DEPTH_ATTACHMENT_READ_WRITE,
                        PrismPassExecutionType.GRAPHICS)
                        == (VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                                | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT),
                "depth accesses must cover early and late fragment tests");
        require(PrismVulkanBarrierPlanner.accessMask(
                        PrismResourceUsageState.DEPTH_ATTACHMENT_READ_WRITE)
                        == (VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                                | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT),
                "depth read/write must expose both access bits");

        PrismBufferDesc storage = new PrismBufferDesc(
                4096,
                EnumSet.of(PrismBufferUsage.STORAGE, PrismBufferUsage.COPY_DST));
        PrismCompiledGraph graph = new PrismRenderGraph()
                .declareResource(PrismResourceDescriptor.transientBuffer("particles", storage))
                .addPass(new PrismPass(
                        "simulate",
                        List.of(new PrismResourceRef(
                                "particles",
                                PrismResourceAccess.WRITE,
                                PrismResourceUsageState.BUFFER_WRITE)),
                        PrismPassExecutionType.COMPUTE))
                .addPass(new PrismPass(
                        "draw",
                        List.of(new PrismResourceRef(
                                "particles",
                                PrismResourceAccess.READ,
                                PrismResourceUsageState.BUFFER_READ)),
                        PrismPassExecutionType.GRAPHICS))
                .compile();
        PrismResourceTransition transition = graph.transitionsTo("draw").stream()
                .filter(candidate -> candidate.resourceName().equals("particles"))
                .findFirst()
                .orElseThrow();
        require(transition.resourceType() == PrismResourceType.BUFFER,
                "storage transition must retain its buffer type");
        require(transition.fromExecutionType() == PrismPassExecutionType.COMPUTE
                        && transition.toExecutionType() == PrismPassExecutionType.GRAPHICS,
                "transition must retain producer and consumer execution domains");
        require(transition.hazard() == PrismResourceHazard.READ_AFTER_WRITE,
                "compute-write to graphics-read must be a RAW hazard");
        require(graph.transitionsFrom("simulate").contains(transition),
                "producer and consumer transition views must share the same plan");
        require(graph.transitionsTo("draw") == graph.transitionsTo("draw")
                        && graph.transitionsFrom("simulate") == graph.transitionsFrom("simulate"),
                "compiled graph transition indices must be reused without per-frame list allocation");

        System.out.println("Dreamveil Prism Vulkan barrier-plan smoke tests: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
