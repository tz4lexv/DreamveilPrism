package dev.dreamveil.prism.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reject state that a raw CPU model stream cannot reproduce faithfully. */
@Mixin(RenderSetup.class)
public interface RenderSetupReplayAccessor {
    @Accessor("textureTransform") TextureTransform prism$textureTransform();
    @Accessor("layeringTransform") LayeringTransform prism$layeringTransform();
}
