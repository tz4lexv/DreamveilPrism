package dev.dreamveil.prism.bridge;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.graph.PrismCompiledGraph;
import dev.dreamveil.prism.graph.PrismPass;
import dev.dreamveil.prism.graph.PrismResourceTransition;
import dev.dreamveil.prism.graph.PrismResourceType;
import dev.dreamveil.prism.graph.PrismResourceUsageState;

/** Runtime validation of the backend-neutral hazard plan against Blaze3D resource usages. */
final class Blaze3DHazardValidator {
    void validateBeforePass(PrismCompiledGraph graph, PrismPass pass, Blaze3DFrameResources resources) {
        for (PrismResourceTransition transition : graph.transitionsTo(pass.name())) {
            var descriptor = graph.requireResource(transition.resourceName());
            if (descriptor.type() != PrismResourceType.TEXTURE) {
                continue;
            }
            GpuTextureView view = resources.requireTexture(transition.resourceName());
            GpuTexture texture = view.texture();
            int usage = texture.usage();

            if (transition.toState() == PrismResourceUsageState.SAMPLED_READ
                    && (usage & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
                throw new IllegalStateException(
                        "Prism hazard validation failed for '" + transition.resourceName()
                                + "': pass '" + pass.name() + "' requires sampled read but texture usage=0x"
                                + Integer.toHexString(usage));
            }

            boolean attachmentWrite = switch (transition.toState()) {
                case COLOR_ATTACHMENT_WRITE, COLOR_ATTACHMENT_READ_WRITE,
                        DEPTH_ATTACHMENT_WRITE, DEPTH_ATTACHMENT_READ_WRITE -> true;
                default -> false;
            };
            if (attachmentWrite && (usage & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
                throw new IllegalStateException(
                        "Prism hazard validation failed for '" + transition.resourceName()
                                + "': pass '" + pass.name() + "' requires render-attachment access but texture usage=0x"
                                + Integer.toHexString(usage));
            }
            boolean storage = switch (transition.toState()) {
                case STORAGE_IMAGE_READ, STORAGE_IMAGE_WRITE, STORAGE_IMAGE_READ_WRITE -> true;
                default -> false;
            };
            if (storage && (usage & PrismVulkanUsage.STORAGE_TEXTURE) == 0) {
                throw new IllegalStateException(
                        "Prism hazard validation failed for '" + transition.resourceName()
                                + "': pass '" + pass.name() + "' requires Vulkan storage-image usage");
            }
        }
    }
}
