package dev.dreamveil.prism.mixin;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.pack.PrismWorldRenderingPipeline;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;

/** Adds scene G-buffer attachments only to READY Prism Feature Rendering variants. */
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMixin {
    @Shadow @Final private RenderPipeline pipeline;

    @Redirect(
            method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"))
    private RenderPass dreamveilPrism$createFeaturePass(
            CommandEncoder encoder,
            Supplier<String> label,
            GpuTextureView color,
            Optional<Vector4fc> colorClear,
            GpuTextureView depth,
            OptionalDouble depthClear) {
        return PrismWorldRenderingPipeline.createFeatureRenderPass(
                encoder, pipeline, label, color, colorClear, depth, depthClear);
    }
}
