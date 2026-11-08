package dev.nitro.mixin;

import dev.nitro.modules.SwingAnimation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    /** Swing Animation: change the swing length of the local player (visual only). */
    @Inject(method = "getHandSwingDuration", at = @At("HEAD"), cancellable = true)
    private void nitro$swingDuration(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this != MinecraftClient.getInstance().player) return;
        int d = SwingAnimation.duration();
        if (d > 0) cir.setReturnValue(d);
    }
}
