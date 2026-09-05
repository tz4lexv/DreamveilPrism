package dev.dreamveil.prism.pack;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK12.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;

import dev.dreamveil.prism.bridge.Blaze3DPassContext;
import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.mixin.CommandEncoderAccessorMixin;

/** Vulkan-native compute pipeline kept behind Prism's backend-neutral pack contract. */
final class PrismCompiledComputePipeline implements AutoCloseable {
    private final String pipelineId;
    private final String passName;
    private final PrismPipelineDefinition definition;
    private final VulkanDevice device;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long sourceFingerprint;
    private boolean closed;
    private boolean firstDispatchRecorded;

    private PrismCompiledComputePipeline(
            String pipelineId,
            String passName,
            PrismPipelineDefinition definition,
            VulkanDevice device,
            long descriptorSetLayout,
            long pipelineLayout,
            long pipeline,
            long sourceFingerprint) {
        this.pipelineId = pipelineId;
        this.passName = passName;
        this.definition = definition;
        this.device = device;
        this.descriptorSetLayout = descriptorSetLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.sourceFingerprint = sourceFingerprint;
    }

    static PrismCompiledComputePipeline compile(
            PrismPackDefinition pack,
            PrismPipelineDefinition definition,
            String passName,
            VulkanDevice device,
            String expandedSource,
            long sourceFingerprint) throws PrismPackLoadException {
        String source = injectAbiDefines(expandedSource, definition);
        ByteBuffer spirv = compileSpirv(source, definition.compute());
        if (pack.generatedSources().containsKey(definition.compute())) {
            java.util.Map<String, Integer> assignments = new java.util.LinkedHashMap<>();
            for (var sampler : definition.samplers()) assignments.put(sampler.name(), assignments.size());
            for (var storage : definition.storageImages()) assignments.put(storage.name(), assignments.size());
            for (var storage : definition.storageBuffers()) assignments.put(storage.name(), assignments.size());
            PrismSpirvReflection.rebind(spirv, assignments, definition.compute());
        }
        long descriptorLayout = VK_NULL_HANDLE;
        long layout = VK_NULL_HANDLE;
        long shaderModule = VK_NULL_HANDLE;
        long nativePipeline = VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = descriptorBindings(stack, definition);
            VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device.vkDevice(), setInfo, null, out),
                    "create descriptor set layout", definition.id());
            descriptorLayout = out.get(0);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout));
            check(vkCreatePipelineLayout(device.vkDevice(), layoutInfo, null, out),
                    "create pipeline layout", definition.id());
            layout = out.get(0);

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(spirv);
            check(vkCreateShaderModule(device.vkDevice(), moduleInfo, null, out),
                    "create compute shader module", definition.id());
            shaderModule = out.get(0);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType$Default().stage(stage).layout(layout);
            check(vkCreateComputePipelines(
                            device.vkDevice(), VK_NULL_HANDLE, pipelineInfo, null, out),
                    "create compute pipeline", definition.id());
            nativePipeline = out.get(0);
            return new PrismCompiledComputePipeline(
                    definition.id(), passName, definition, device,
                    descriptorLayout, layout, nativePipeline, sourceFingerprint);
        } catch (PrismPackLoadException failure) {
            destroyImmediate(device, nativePipeline, layout, descriptorLayout);
            throw failure;
        } catch (RuntimeException | Error failure) {
            destroyImmediate(device, nativePipeline, layout, descriptorLayout);
            throw new PrismPackLoadException(
                    "compute_pipeline_build",
                    "Could not build Vulkan compute pipeline '" + definition.id() + "': " + failure.getMessage(),
                    definition.compute(), failure);
        } finally {
            if (shaderModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device.vkDevice(), shaderModule, null);
            }
        }
    }

    String pipelineId() { return pipelineId; }
    String passName() { return passName; }
    long sourceFingerprint() { return sourceFingerprint; }
    boolean usesFrameUniforms() { return definition.frameUniforms(); }
    boolean hasSamplers() { return !definition.samplers().isEmpty(); }

    void execute(
            Blaze3DPassContext context,
            Map<String, GpuSampler> samplerCache,
            GpuBuffer frameUniformBuffer) {
        if (closed) throw new IllegalStateException("Compute pipeline is closed: " + pipelineId);
        CommandEncoder frameEncoder = context.requireCommandEncoder();
        Object nativeEncoder = ((CommandEncoderAccessorMixin) (Object) frameEncoder).prism$getBackend();
        if (!(nativeEncoder instanceof VulkanCommandEncoder encoder)) {
            throw new IllegalStateException(
                    "Compute pipeline '" + pipelineId + "' requires the live Vulkan frame encoder");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long descriptorPool = createDescriptorPool(stack);
            boolean queued = false;
            try {
                long descriptorSet = allocateDescriptorSet(stack, descriptorPool);
                updateDescriptorSet(stack, descriptorSet, context, samplerCache, frameUniformBuffer);

                var commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
                int incomingBarriers = PrismVulkanBarrierPlanner.applyBeforeCompute(
                        commandBuffer, context.incomingTransitions(), context, stack);
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(
                        commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                        0, stack.longs(descriptorSet), null);
                int[] groups = dispatchGroups(context);
                vkCmdDispatch(commandBuffer, groups[0], groups[1], groups[2]);
                int outgoingBarriers = PrismVulkanBarrierPlanner.applyAfterCompute(
                        commandBuffer, context.outgoingTransitions(), context, stack);
                encoder.execute(commandBuffer);
                if (!firstDispatchRecorded) {
                    firstDispatchRecorded = true;
                    PrismMod.LOGGER.info(
                            "Prism Vulkan compute dispatch joined live frame submission: pass={}, pipeline={}, groups={}x{}x{}, graphBarriersIn={}, graphBarriersOut={}, frameEncoderId={}, nativeEncoderId={}",
                            passName, pipelineId, groups[0], groups[1], groups[2],
                            incomingBarriers, outgoingBarriers,
                            Integer.toHexString(System.identityHashCode(frameEncoder)),
                            Integer.toHexString(System.identityHashCode(encoder)));
                }
                encoder.queueForDestroy(() -> vkDestroyDescriptorPool(device.vkDevice(), descriptorPool, null));
                queued = true;
            } finally {
                if (!queued) vkDestroyDescriptorPool(device.vkDevice(), descriptorPool, null);
            }
        }
    }

    private long createDescriptorPool(MemoryStack stack) {
        int samplerCount = definition.samplers().size();
        int imageCount = definition.storageImages().size();
        int bufferCount = definition.storageBuffers().size();
        int uniformCount = definition.frameUniforms() ? 1 : 0;
        int kinds = (samplerCount > 0 ? 1 : 0) + (imageCount > 0 ? 1 : 0)
                + (bufferCount > 0 ? 1 : 0) + (uniformCount > 0 ? 1 : 0);
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(kinds, stack);
        int index = 0;
        if (samplerCount > 0) sizes.get(index++).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(samplerCount);
        if (imageCount > 0) sizes.get(index++).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(imageCount);
        if (bufferCount > 0) sizes.get(index++).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(bufferCount);
        if (uniformCount > 0) sizes.get(index).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(uniformCount);
        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(1).pPoolSizes(sizes);
        LongBuffer out = stack.mallocLong(1);
        checkRuntime(vkCreateDescriptorPool(device.vkDevice(), info, null, out), "create descriptor pool");
        return out.get(0);
    }

    private long allocateDescriptorSet(MemoryStack stack, long pool) {
        VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(descriptorSetLayout));
        LongBuffer out = stack.mallocLong(1);
        checkRuntime(vkAllocateDescriptorSets(device.vkDevice(), info, out), "allocate descriptor set");
        return out.get(0);
    }

    private void updateDescriptorSet(
            MemoryStack stack,
            long set,
            Blaze3DPassContext context,
            Map<String, GpuSampler> samplerCache,
            GpuBuffer frameUniformBuffer) {
        int count = bindingCount(definition);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(count, stack);
        int binding = 0;
        for (PrismSamplerBinding sampler : definition.samplers()) {
            GpuTextureView genericView = context.requireTexture(
                    PrismPackResources.toInternal(sampler.resource(), sampler.previousHistory()));
            GpuSampler genericSampler = samplerCache.get(sampler.samplerKey());
            if (!(genericView instanceof VulkanGpuTextureView view)
                    || !(genericSampler instanceof VulkanGpuSampler vkSampler)) {
                throw new IllegalStateException("Compute sampler '" + sampler.name() + "' is not Vulkan-backed");
            }
            VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(vkSampler.vkSampler()).imageView(view.vkImageView())
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(binding).sType$Default().dstSet(set).dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(image);
            binding++;
        }
        for (PrismStorageBinding storage : definition.storageImages()) {
            GpuTextureView genericView = context.requireTexture(
                    PrismPackResources.toInternal(storage.resource(), storage.previousHistory()));
            if (!(genericView instanceof VulkanGpuTextureView view)) {
                throw new IllegalStateException("Storage image '" + storage.name() + "' is not Vulkan-backed");
            }
            VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(view.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(binding).sType$Default().dstSet(set).dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).pImageInfo(image);
            binding++;
        }
        for (PrismStorageBinding storage : definition.storageBuffers()) {
            GpuBuffer generic = context.requireBuffer(PrismPackResources.toInternal(storage.resource()));
            if (!(generic instanceof VulkanGpuBuffer buffer)) {
                throw new IllegalStateException("Storage buffer '" + storage.name() + "' is not Vulkan-backed");
            }
            VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer.vkBuffer()).offset(0).range(buffer.size());
            writes.get(binding).sType$Default().dstSet(set).dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).pBufferInfo(info);
            binding++;
        }
        if (definition.frameUniforms()) {
            if (!(frameUniformBuffer instanceof VulkanGpuBuffer buffer)) {
                throw new IllegalStateException("PrismFrame compute UBO is unavailable or not Vulkan-backed");
            }
            VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer.vkBuffer()).offset(0).range(buffer.size());
            writes.get(binding).sType$Default().dstSet(set).dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).pBufferInfo(info);
        }
        vkUpdateDescriptorSets(device.vkDevice(), writes, null);
    }

    private int[] dispatchGroups(Blaze3DPassContext context) {
        PrismComputeDispatch dispatch = definition.dispatch();
        if (!dispatch.resourceDriven()) {
            return new int[] {dispatch.groupsX(), dispatch.groupsY(), dispatch.groupsZ()};
        }
        PrismStorageBinding binding = definition.storageImages().stream()
                .filter(candidate -> candidate.resource().equals(dispatch.resource()))
                .findFirst().orElseThrow();
        GpuTextureView view = context.requireTexture(
                PrismPackResources.toInternal(binding.resource(), binding.previousHistory()));
        return new int[] {
                ceilDiv(view.getWidth(0), dispatch.localSizeX()),
                ceilDiv(view.getHeight(0), dispatch.localSizeY()),
                1
        };
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static VkDescriptorSetLayoutBinding.Buffer descriptorBindings(
            MemoryStack stack,
            PrismPipelineDefinition definition) {
        VkDescriptorSetLayoutBinding.Buffer result = VkDescriptorSetLayoutBinding.calloc(bindingCount(definition), stack);
        int binding = 0;
        for (int ignored = 0; ignored < definition.samplers().size(); ignored++) {
            result.get(binding).binding(binding).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binding++;
        }
        for (int ignored = 0; ignored < definition.storageImages().size(); ignored++) {
            result.get(binding).binding(binding).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binding++;
        }
        for (int ignored = 0; ignored < definition.storageBuffers().size(); ignored++) {
            result.get(binding).binding(binding).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binding++;
        }
        if (definition.frameUniforms()) {
            result.get(binding).binding(binding).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        }
        return result;
    }

    private static int bindingCount(PrismPipelineDefinition definition) {
        return definition.samplers().size() + definition.storageImages().size()
                + definition.storageBuffers().size() + (definition.frameUniforms() ? 1 : 0);
    }

    static String injectAbiDefines(String source, PrismPipelineDefinition definition) {
        List<String> defines = new ArrayList<>();
        defines.add("#define PRISM_COMPUTE 1");
        int binding = 0;
        for (PrismSamplerBinding value : definition.samplers()) {
            defines.add("#define PRISM_BINDING_" + macro(value.name()) + " " + binding++);
        }
        for (PrismStorageBinding value : definition.storageImages()) {
            defines.add("#define PRISM_BINDING_" + macro(value.name()) + " " + binding++);
        }
        for (PrismStorageBinding value : definition.storageBuffers()) {
            defines.add("#define PRISM_BINDING_" + macro(value.name()) + " " + binding++);
        }
        if (definition.frameUniforms()) defines.add("#define PRISM_BINDING_FRAME " + binding);
        PrismComputeDispatch dispatch = definition.dispatch();
        defines.add("#define PRISM_LOCAL_SIZE_X " + (dispatch.resourceDriven() ? dispatch.localSizeX() : 1));
        defines.add("#define PRISM_LOCAL_SIZE_Y " + (dispatch.resourceDriven() ? dispatch.localSizeY() : 1));
        defines.add("#define PRISM_LOCAL_SIZE_Z " + (dispatch.resourceDriven() ? dispatch.localSizeZ() : 1));
        int newline = source.indexOf('\n');
        if (newline < 0) return source + '\n' + String.join("\n", defines) + '\n';
        return source.substring(0, newline + 1) + String.join("\n", defines) + '\n'
                + source.substring(newline + 1);
    }

    private static String macro(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    static ByteBuffer compileSpirv(String source, String path) throws PrismPackLoadException {
        return compileSpirv(source, path, shaderc_compute_shader, path.startsWith("$prism/"));
    }

    static ByteBuffer compileSpirv(String source, String path, int stage, boolean automaticBindings) throws PrismPackLoadException {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        if (compiler == 0L || options == 0L) {
            if (options != 0L) shaderc_compile_options_release(options);
            if (compiler != 0L) shaderc_compiler_release(compiler);
            throw new PrismPackLoadException("compute_compiler", "Could not initialize shaderc", path);
        }
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_source_language(options, shaderc_source_language_glsl);
            if (automaticBindings) {
                shaderc_compile_options_set_auto_bind_uniforms(options, true);
                shaderc_compile_options_set_auto_map_locations(options, true);
                shaderc_compile_options_set_generate_debug_info(options);
                // Check expanded macro declarations too: never silently override a creator's
                // explicit descriptor layout while normalizing the generated ABI.
                long preprocessed = shaderc_compile_into_preprocessed_text(compiler, source, stage, path, "main", options);
                if (preprocessed == 0L) throw new PrismPackLoadException("frontend_preprocess", "shaderc returned no preprocessed source", path);
                try {
                    if (shaderc_result_get_compilation_status(preprocessed) != shaderc_compilation_status_success) {
                        throw new PrismPackLoadException("frontend_preprocess", shaderc_result_get_error_message(preprocessed), path);
                    }
                    String expanded = java.nio.charset.StandardCharsets.UTF_8.decode(shaderc_result_get_bytes(preprocessed)).toString();
                    if (PrismMetadataParser.withoutComments(expanded).matches("(?s).*\\blayout\\s*\\([^)]*\\b(?:set|binding)\\s*=.*")) {
                        throw new PrismPackLoadException("frontend_manual_binding", "Remove explicit set/binding declarations from this frontend; Prism will not silently override them", path);
                    }
                } finally { shaderc_result_release(preprocessed); }
            }
            long result = shaderc_compile_into_spv(
                    compiler, source, stage, path, "main", options);
            if (result == 0L) {
                throw new PrismPackLoadException("compute_compile", "shaderc returned no result", path);
            }
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new PrismPackLoadException(
                            "compute_compile", shaderc_result_get_error_message(result), path);
                }
                ByteBuffer nativeBytes = shaderc_result_get_bytes(result);
                ByteBuffer copy = ByteBuffer.allocateDirect(nativeBytes.remaining());
                copy.put(nativeBytes).flip();
                return copy;
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private static void check(int result, String operation, String pipelineId) throws PrismPackLoadException {
        if (result != VK_SUCCESS) {
            throw new PrismPackLoadException(
                    "compute_vulkan", "Vulkan failed to " + operation + " for '" + pipelineId
                            + "' (VkResult " + result + ")", "prism.json");
        }
    }

    private static void checkRuntime(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
    }

    private static void destroyImmediate(VulkanDevice device, long pipeline, long layout, long descriptorLayout) {
        if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device.vkDevice(), pipeline, null);
        if (layout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device.vkDevice(), layout, null);
        if (descriptorLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device.vkDevice(), descriptorLayout, null);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Destroyable destruction = () -> destroyImmediate(device, pipeline, pipelineLayout, descriptorSetLayout);
        device.createCommandEncoder().queueForDestroy(destruction);
    }
}
