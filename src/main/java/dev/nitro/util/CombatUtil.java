package dev.nitro.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.Item;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Shared helpers for the Combat modules: hotbar handling and explosion damage estimates. */
public final class CombatUtil {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private CombatUtil() {}

    /** Hotbar index (0-8) holding the item, or -1. */
    public static int findHotbar(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    /** Selects a hotbar slot and tells the server. */
    public static void selectHotbar(int slot) {
        if (mc.player == null || mc.getNetworkHandler() == null || slot < 0 || slot > 8) return;
        if (mc.player.getInventory().selectedSlot == slot) return;
        mc.player.getInventory().selectedSlot = slot;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    /** Health plus absorption. */
    public static float effectiveHealth(LivingEntity e) {
        return e.getHealth() + e.getAbsorptionAmount();
    }

    /**
     * Estimated damage an end crystal (power 6) exploding at {@code src} deals to {@code e}.
     * Uses a coarse 12-ray exposure test and approximates armor and Resistance; enchantments are covered by
     * {@code protection} (0 = none, 0.5 = halve the damage).
     */
    public static float crystalDamage(Vec3d src, LivingEntity e, double protection) {
        return explosionDamage(src, e, 6.0f, protection);
    }

    /** Respawn anchor explosions have power 5. */
    public static float anchorDamage(Vec3d src, LivingEntity e, double protection) {
        return explosionDamage(src, e, 5.0f, protection);
    }

    public static float explosionDamage(Vec3d src, LivingEntity e, float power, double protection) {
        if (mc.world == null) return 0;
        double diameter = power * 2.0;
        double dist = Math.sqrt(e.squaredDistanceTo(src)) / diameter;
        if (dist > 1.0) return 0;
        double exposure = exposure(src, e);
        if (exposure <= 0) return 0;
        double impact = (1.0 - dist) * exposure;
        float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);

        switch (mc.world.getDifficulty()) {
            case PEACEFUL -> dmg = 0;
            case EASY -> dmg = Math.min(dmg / 2f + 1f, dmg);
            case HARD -> dmg *= 1.5f;
            default -> { }
        }

        double armor = e.getArmor();
        double tough = e.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        double f = 2.0 + tough / 4.0;
        double red = MathHelper.clamp(armor - dmg / f, armor * 0.2, 20.0) / 25.0;
        dmg *= (float) (1.0 - red);

        StatusEffectInstance res = e.getStatusEffect(StatusEffects.RESISTANCE);
        if (res != null) dmg *= (float) Math.max(0.0, 1.0 - 0.2 * (res.getAmplifier() + 1));

        dmg *= (float) (1.0 - MathHelper.clamp(protection, 0.0, 0.8));
        return Math.max(0f, dmg);
    }

    private static double exposure(Vec3d src, Entity e) {
        Box b = e.getBoundingBox();
        final double[] xs = {0.2, 0.8};
        final double[] ys = {0.1, 0.5, 0.9};
        int hit = 0, total = 0;
        for (double fx : xs) {
            for (double fy : ys) {
                for (double fz : xs) {
                    Vec3d p = new Vec3d(
                            MathHelper.lerp(fx, b.minX, b.maxX),
                            MathHelper.lerp(fy, b.minY, b.maxY),
                            MathHelper.lerp(fz, b.minZ, b.maxZ));
                    total++;
                    var r = mc.world.raycast(new RaycastContext(p, src,
                            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, e));
                    if (r.getType() == net.minecraft.util.hit.HitResult.Type.MISS) hit++;
                }
            }
        }
        return total == 0 ? 0 : (double) hit / total;
    }
}
