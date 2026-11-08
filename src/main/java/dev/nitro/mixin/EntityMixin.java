package dev.nitro.mixin;

import dev.nitro.modules.Freecam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    /** While freecam is active, mouse movement turns the camera instead of the player. */
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void nitro$freecamLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if (!Freecam.active()) return;
        if ((Object) this != MinecraftClient.getInstance().player) return;
        Freecam.INSTANCE.look(cursorDeltaX, cursorDeltaY);
        ci.cancel();
    }
}
