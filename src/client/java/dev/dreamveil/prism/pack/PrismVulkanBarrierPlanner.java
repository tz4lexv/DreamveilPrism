package dev.dreamveil.prism.pack;

import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK12.VK_REMAINING_ARRAY_LAYERS;
import static org.lwjgl.vulkan.VK13.*;

import java.util.List;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;

import dev.dreamveil.prism.bridge.Blaze3DPassContext;
import dev.dreamveil.prism.graph.PrismPassExecutionType;
import dev.dreamveil.prism.graph.PrismResourceTransition;
import dev.dreamveil.prism.graph.PrismResourceType;
import dev.dreamveil.prism.graph.PrismResourceUsageState;

/** Converts graph hazards into Vulkan Synchronization2 barriers scoped to concrete resources. */
final class PrismVulkanBarrierPlanner {
    private PrismVulkanBarrierPlanner() {}

    static int applyBeforeCompute(
            VkCommandBuffer commandBuffer,
            List<PrismResourceTransition> transitions,
            Blaze3DPassContext context,
            MemoryStack stack) {
        return apply(commandBuffer, transitions, context, stack, true);
    }

    static int applyAfterCompute(
            VkCommandBuffer commandBuffer,
            List<PrismResourceTransition> transitions,
            Blaze3DPassContext context,
            MemoryStack stack) {
        return apply(commandBuffer, transitions, context, stack, false);
    }

    private static int apply(
            VkCommandBuffer commandBuffer,
            List<PrismResourceTransition> transitions,
            Blaze3DPassContext context,
            MemoryStack stack,
            boolean beforeCompute) {
        int requiredCount = 0;
        int imageCount = 0;
        for (PrismResourceTransition transition : transitions) {
            if (!requiresBarrier(transition, beforeCompute)) continue;
            requiredCount++;
            if (transition.resourceType() == PrismResourceType.TEXTURE) imageCount++;
        }
        if (requiredCount == 0) return 0;

        int bufferCount = requiredCount - imageCount;
        VkImageMemoryBarrier2.Buffer imageBarriers = imageCount == 0
                ? null : VkImageMemoryBarrier2.calloc(imageCount, stack);
        VkBufferMemoryBarrier2.Buffer bufferBarriers = bufferCount == 0
                ? null : VkBufferMemoryBarrier2.calloc(bufferCount, stack);

        int imageIndex = 0;
        int bufferIndex = 0;
        for (PrismResourceTransition transition : transitions) {
            if (!requiresBarrier(transition, beforeCompute)) continue;
            if (transition.resourceType() == PrismResourceType.TEXTURE) {
                GpuTextureView genericView = context.requireTexture(transition.resourceName());
                if (!(genericView instanceof VulkanGpuTextureView view)
                        || !(view.texture() instanceof VulkanGpuTexture texture)) {
                    throw new IllegalStateException(
                            "Graph barrier resource '" + transition.resourceName() + "' is not Vulkan-backed");
                }
                GpuFormat format = texture.getFormat();
                int aspect = 0;
                if (format.hasColorAspect()) aspect |= VK_IMAGE_ASPECT_COLOR_BIT;
                if (format.hasDepthAspect()) aspect |= VK_IMAGE_ASPECT_DEPTH_BIT;
                if (format.hasStencilAspect()) aspect |= VK_IMAGE_ASPECT_STENCIL_BIT;

                VkImageMemoryBarrier2 barrier = imageBarriers.get(imageIndex++)
                        .sType$Default()
                        .srcStageMask(stageMask(transition.fromState(), transition.fromExecutionType()))
                        .srcAccessMask(accessMask(transition.fromState()))
                        .dstStageMask(stageMask(transition.toState(), transition.toExecutionType()))
                        .dstAccessMask(accessMask(transition.toState()))
                        .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(texture.vkImage());
                barrier.subresourceRange()
                        .aspectMask(aspect)
                        .baseMipLevel(view.baseMipLevel())
                        .levelCount(view.mipLevels())
                        .baseArrayLayer(0)
                        .layerCount(VK_REMAINING_ARRAY_LAYERS);
                continue;
            }

            var genericBuffer = context.requireBuffer(transition.resourceName());
            if (!(genericBuffer instanceof VulkanGpuBuffer buffer)) {
                throw new IllegalStateException(
                        "Graph barrier resource '" + transition.resourceName() + "' is not Vulkan-backed");
            }
            bufferBarriers.get(bufferIndex++)
                    .sType$Default()
                    .srcStageMask(stageMask(transition.fromState(), transition.fromExecutionType()))
                    .srcAccessMask(accessMask(transition.fromState()))
                    .dstStageMask(stageMask(transition.toState(), transition.toExecutionType()))
                    .dstAccessMask(accessMask(transition.toState()))
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer.vkBuffer())
                    .offset(0)
                    .size(VK_WHOLE_SIZE);
        }

        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default();
        if (imageBarriers != null) dependency.pImageMemoryBarriers(imageBarriers);
        if (bufferBarriers != null) dependency.pBufferMemoryBarriers(bufferBarriers);
        vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        return requiredCount;
    }

    private static boolean requiresBarrier(
            PrismResourceTransition transition,
            boolean beforeCompute) {
        if (!transition.requiresSynchronization()
                || transition.fromState() == PrismResourceUsageState.UNDEFINED) {
            return false;
        }
        // A compute producer records this dependency after its own dispatch. Do not
        // repeat it before the next compute dispatch in the same ordered submission.
        return !beforeCompute || transition.fromExecutionType() != PrismPassExecutionType.COMPUTE;
    }

    static long stageMask(PrismResourceUsageState state, PrismPassExecutionType executionType) {
        return switch (state) {
            case UNDEFINED -> VK_PIPELINE_STAGE_2_NONE;
            case COLOR_ATTACHMENT_WRITE, COLOR_ATTACHMENT_READ_WRITE ->
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
            case DEPTH_ATTACHMENT_WRITE, DEPTH_ATTACHMENT_READ_WRITE ->
                    VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                            | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
            case SAMPLED_READ -> executionType == PrismPassExecutionType.COMPUTE
                    ? VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                    : VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
            case STORAGE_IMAGE_READ, STORAGE_IMAGE_WRITE, STORAGE_IMAGE_READ_WRITE ->
                    executionType == PrismPassExecutionType.GRAPHICS
                            ? VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                            : VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
            case BUFFER_READ, BUFFER_WRITE, BUFFER_READ_WRITE ->
                    executionType == PrismPassExecutionType.GRAPHICS
                            ? VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT
                            : VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        };
    }

    static long accessMask(PrismResourceUsageState state) {
        return switch (state) {
            case UNDEFINED -> VK_ACCESS_2_NONE;
            case SAMPLED_READ -> VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
            case COLOR_ATTACHMENT_WRITE -> VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
            case COLOR_ATTACHMENT_READ_WRITE ->
                    VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
            case DEPTH_ATTACHMENT_WRITE -> VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case DEPTH_ATTACHMENT_READ_WRITE ->
                    VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                            | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case STORAGE_IMAGE_READ, BUFFER_READ -> VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
            case STORAGE_IMAGE_WRITE, BUFFER_WRITE -> VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
            case STORAGE_IMAGE_READ_WRITE, BUFFER_READ_WRITE ->
                    VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
        };
    }
}
