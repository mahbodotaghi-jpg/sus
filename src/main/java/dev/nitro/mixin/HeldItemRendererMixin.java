package dev.nitro.mixin;

import dev.nitro.modules.SwingAnimation;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    /** Swing Animation: replace the vanilla swing rotation of the first-person item. */
    @Inject(method = "applySwingOffset", at = @At("HEAD"), cancellable = true)
    private void nitro$swing(MatrixStack matrices, Arm arm, float swingProgress, CallbackInfo ci) {
        if (!SwingAnimation.applies(arm)) return;
        SwingAnimation.apply(matrices, arm, swingProgress);
        ci.cancel();
    }
}
