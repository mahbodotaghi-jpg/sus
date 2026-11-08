package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.CombatUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Crystal: places end crystals on obsidian/bedrock and breaks them, choosing the spot that hurts nearby players
 * most while keeping your own damage under a limit. Damage values are estimates (no enchantment info).
 */
public final class AutoCrystal extends Module {
    public enum SwitchMode {
        NONE("Hold only"), AUTO("Auto switch");
        private final String label;
        SwitchMode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum Mode {
        HOLD("Hold right click"), AURA("Aura (automatic)");
        private final String label;
        Mode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    private final Setting.Choice<Mode> mode = new Setting.Choice<>("Mode", Mode.HOLD, Mode.class);
    private final Setting.Num placeCps = new Setting.Num("Place speed (per second)", 10, 1, 20, 1);
    private final Setting.Num breakCps = new Setting.Num("Break speed (per second)", 10, 1, 20, 1);
    private final Setting.Bool place = new Setting.Bool("Place", true);
    private final Setting.Bool brk = new Setting.Bool("Break", true);
    private final Setting.Num placeRange = new Setting.Num("Place range", 4.5, 1, 6, 0.5);
    private final Setting.Num breakRange = new Setting.Num("Break range", 4.5, 1, 6, 0.5);
    private final Setting.Num targetRange = new Setting.Num("Target range", 10, 3, 16, 1);
    private final Setting.Num placeDelay = new Setting.Num("Place delay (ticks)", 2, 0, 10, 1);
    private final Setting.Num breakDelay = new Setting.Num("Break delay (ticks)", 1, 0, 10, 1);
    private final Setting.Num minDamage = new Setting.Num("Min target damage", 5, 0, 36, 0.5);
    private final Setting.Num maxSelf = new Setting.Num("Max self damage", 6, 0, 36, 0.5);
    private final Setting.Bool antiSuicide = new Setting.Bool("Never kill myself", true);
    private final Setting.Num facePlace = new Setting.Num("Face place below target health (0 = off)", 8, 0, 36, 1);
    private final Setting.Num prot = new Setting.Num("Assumed enchant reduction", 0.0, 0.0, 0.8, 0.05);
    private final Setting.Choice<SwitchMode> sw = new Setting.Choice<>("Crystal switch", SwitchMode.AUTO, SwitchMode.class);
    private final Setting.Bool pauseUse = new Setting.Bool("Pause while eating/using", true);

    private int placeTimer, breakTimer;
    private float placeAcc, breakAcc;

    public AutoCrystal() {
        super("Auto Crystal", "Hold right click to place and break crystals at your crosshair, or run in automatic mode.", Category.COMBAT);
        add(mode, placeCps, breakCps, place, brk, placeRange, breakRange, targetRange, placeDelay, breakDelay, minDamage,
                maxSelf, antiSuicide, facePlace, prot, sw, pauseUse);
        // speed sliders belong to Hold mode, everything damage/range based to Aura mode
        placeCps.showIf(() -> mode.value == Mode.HOLD && place.on());
        breakCps.showIf(() -> mode.value == Mode.HOLD && brk.on());
        for (Setting<?> x : new Setting<?>[]{placeRange, breakRange, targetRange, placeDelay, breakDelay, minDamage,
                maxSelf, antiSuicide, facePlace, prot}) {
            x.showIf(() -> mode.value == Mode.AURA);
        }
    }

    @Override
    public void onEnable() {
        placeTimer = breakTimer = 0;
        placeAcc = breakAcc = 1f;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;
        if (pauseUse.on() && mc.player.isUsingItem()) return;
        if (mode.value == Mode.HOLD) {
            holdTick();
            return;
        }
        if (placeTimer > 0) placeTimer--;
        if (breakTimer > 0) breakTimer--;

        List<AbstractClientPlayerEntity> targets = targets();
        if (targets.isEmpty()) return;

        if (brk.on() && breakTimer <= 0 && doBreak(targets)) breakTimer = breakDelay.i();
        if (place.on() && placeTimer <= 0 && doPlace(targets)) placeTimer = placeDelay.i();
    }

    /**
     * Hold mode: while right click is held, a crystal under the crosshair is broken and an obsidian/bedrock block under
     * the crosshair gets a crystal. Each action runs at its own speed (per second, max 1 per tick).
     */
    private void holdTick() {
        if (!mc.options.useKey.isPressed()) {
            placeAcc = breakAcc = 1f; // first action after pressing is instant
            return;
        }
        placeAcc = Math.min(1f, placeAcc + placeCps.f() / 20f);
        breakAcc = Math.min(1f, breakAcc + breakCps.f() / 20f);

        HitResult ht = mc.crosshairTarget;
        if (ht == null) return;

        if (brk.on() && breakAcc >= 1f && ht instanceof EntityHitResult er && er.getEntity() instanceof EndCrystalEntity c
                && c.isAlive()) {
            mc.interactionManager.attackEntity(mc.player, c);
            mc.player.swingHand(Hand.MAIN_HAND);
            breakAcc -= 1f;
            return;
        }

        if (place.on() && placeAcc >= 1f && ht instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos p = bhr.getBlockPos();
            BlockState st = mc.world.getBlockState(p);
            if (!st.isOf(Blocks.OBSIDIAN) && !st.isOf(Blocks.BEDROCK)) return;
            if (!mc.world.getBlockState(p.up()).isAir() || !mc.world.getBlockState(p.up(2)).isAir()) return;
            Box box = new Box(p.getX(), p.getY() + 1, p.getZ(), p.getX() + 1, p.getY() + 3, p.getZ() + 1);
            if (!mc.world.getOtherEntities(null, box, e -> !e.isSpectator()).isEmpty()) return;
            Hand hand = crystalHand();
            if (hand == null) return;
            if (mc.interactionManager.interactBlock(mc.player, hand, bhr).isAccepted()) {
                mc.player.swingHand(hand);
                placeAcc -= 1f;
            }
        }
    }

    /** Hand holding crystals (switching to a hotbar crystal if allowed), or null. */
    private Hand crystalHand() {
        if (mc.player.getOffHandStack().isOf(Items.END_CRYSTAL)) return Hand.OFF_HAND;
        if (mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) return Hand.MAIN_HAND;
        int slot = CombatUtil.findHotbar(Items.END_CRYSTAL);
        if (sw.value == SwitchMode.AUTO && slot >= 0) {
            CombatUtil.selectHotbar(slot);
            return Hand.MAIN_HAND;
        }
        return null;
    }

    private List<AbstractClientPlayerEntity> targets() {
        List<AbstractClientPlayerEntity> out = new ArrayList<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;
            if (mc.player.distanceTo(p) > targetRange.d()) continue;
            out.add(p);
        }
        return out;
    }

    /** Returns {bestTargetDamage, thresholdForThatTarget}, or null when self damage is not acceptable. */
    private float[] evaluate(Vec3d src, List<AbstractClientPlayerEntity> targets) {
        float self = CombatUtil.crystalDamage(src, mc.player, prot.d());
        if (self > maxSelf.d()) return null;
        if (antiSuicide.on() && self >= CombatUtil.effectiveHealth(mc.player) - 0.5f) return null;

        float best = 0, need = (float) minDamage.d();
        for (AbstractClientPlayerEntity t : targets) {
            if (t.squaredDistanceTo(src) > 144) continue;
            float d = CombatUtil.crystalDamage(src, t, prot.d());
            boolean face = facePlace.d() > 0 && CombatUtil.effectiveHealth(t) <= facePlace.d();
            float thr = face ? 1f : (float) minDamage.d();
            if (d >= thr && d > best) {
                best = d;
                need = thr;
            }
        }
        return best > 0 ? new float[]{best, need} : null;
    }

    private boolean doBreak(List<AbstractClientPlayerEntity> targets) {
        EndCrystalEntity bestC = null;
        float bestD = 0;
        double r2 = breakRange.d() * breakRange.d();
        Vec3d eye = mc.player.getEyePos();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof EndCrystalEntity c) || !c.isAlive()) continue;
            if (eye.squaredDistanceTo(c.getPos().add(0, 0.5, 0)) > r2) continue;
            float[] ev = evaluate(c.getPos(), targets);
            if (ev != null && ev[0] > bestD) {
                bestD = ev[0];
                bestC = c;
            }
        }
        if (bestC == null) return false;
        mc.interactionManager.attackEntity(mc.player, bestC);
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private boolean doPlace(List<AbstractClientPlayerEntity> targets) {
        Hand hand = crystalHand();
        if (hand == null) return false;

        Vec3d eye = mc.player.getEyePos();
        int r = (int) Math.ceil(placeRange.d());
        BlockPos base = mc.player.getBlockPos();
        double r2 = placeRange.d() * placeRange.d();

        BlockPos bestPos = null;
        float bestDmg = 0;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = base.add(x, y, z);
                    if (eye.squaredDistanceTo(Vec3d.ofCenter(p)) > r2) continue;
                    BlockState st = mc.world.getBlockState(p);
                    if (!st.isOf(Blocks.OBSIDIAN) && !st.isOf(Blocks.BEDROCK)) continue;
                    if (!mc.world.getBlockState(p.up()).isAir() || !mc.world.getBlockState(p.up(2)).isAir()) continue;
                    Box box = new Box(p.getX(), p.getY() + 1, p.getZ(), p.getX() + 1, p.getY() + 3, p.getZ() + 1);
                    if (!mc.world.getOtherEntities(null, box, e -> !e.isSpectator()).isEmpty()) continue;

                    Vec3d src = new Vec3d(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5);
                    float[] ev = evaluate(src, targets);
                    if (ev != null && ev[0] > bestDmg) {
                        bestDmg = ev[0];
                        bestPos = p;
                    }
                }
            }
        }
        if (bestPos == null) return false;
        BlockHitResult hit = new BlockHitResult(
                new Vec3d(bestPos.getX() + 0.5, bestPos.getY() + 1.0, bestPos.getZ() + 0.5),
                Direction.UP, bestPos, false);
        if (mc.interactionManager.interactBlock(mc.player, hand, hit).isAccepted()) {
            mc.player.swingHand(hand);
            return true;
        }
        return false;
    }
}
