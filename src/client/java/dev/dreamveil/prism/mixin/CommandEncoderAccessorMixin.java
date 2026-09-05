package dev.dreamveil.prism.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;

/** Exposes the live frame encoder backend so native Prism commands join the same submission. */
@Mixin(CommandEncoder.class)
public interface CommandEncoderAccessorMixin {
    @Accessor("backend")
    CommandEncoderBackend prism$getBackend();
}
