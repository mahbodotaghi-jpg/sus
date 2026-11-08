package dev.nitro.mixin;

import dev.nitro.modules.Freecam;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("RETURN"))
    private void nitro$freecamPose(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
                                   float tickDelta, CallbackInfo ci) {
        if (!Freecam.active()) return;
        Freecam f = Freecam.INSTANCE;
        setPos(f.x(tickDelta), f.y(tickDelta), f.z(tickDelta));
        setRotation(f.yaw, f.pitch);
    }

    /** Makes the vanilla renderer draw the player model (and hide the first-person hand) while in freecam. */
    @Inject(method = "isThirdPerson", at = @At("HEAD"), cancellable = true)
    private void nitro$showPlayer(CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.active() && Freecam.INSTANCE.showPlayer.on()) cir.setReturnValue(true);
    }
}
