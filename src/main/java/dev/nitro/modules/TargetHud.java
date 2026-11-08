package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.Gfx;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * Target HUD: animated card for the player you are looking at (or the nearest one).
 * Fades and pops in/out, smooth health bar with a damage trail, hurt flash on the head, win/lose tag, armor and items.
 */
public final class TargetHud extends Module {
    public enum Mode {
        CROSSHAIR("Crosshair"), NEAREST("Nearest player");
        private final String label;
        Mode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    private final Setting.Choice<Mode> mode = new Setting.Choice<>("Target", Mode.CROSSHAIR, Mode.class);
    private final Setting.Num range = new Setting.Num("Nearest range", 24, 4, 128, 1);
    private final Setting.Num hold = new Setting.Num("Hold time (s)", 2, 0, 10, 0.5);
    private final Setting.Choice<Gfx.PanelStyle> style = new Setting.Choice<>("Style", Gfx.PanelStyle.GLASS, Gfx.PanelStyle.class);
    private final Setting.Num scale = new Setting.Num("Scale", 1.0, 0.6, 1.8, 0.05);
    private final Setting.Bool head = new Setting.Bool("Show head", true);
    private final Setting.Bool armor = new Setting.Bool("Show armor & items", true);
    private final Setting.Bool distance = new Setting.Bool("Show distance & armor value", true);
    private final Setting.Bool winLose = new Setting.Bool("Win / lose tag", true);
    private final Setting.Num offX = new Setting.Num("X offset", 24, -400, 400, 1);
    private final Setting.Num offY = new Setting.Num("Y offset", 24, -300, 300, 1);
    private final Setting.Color tint = new Setting.Color("Panel tint", 0x20242C);
    private final Setting.Num opacity = new Setting.Num("Panel opacity", 65, 20, 95, 5);
    private final Setting.Color accent = new Setting.Color("Accent", 0x6EA8FF);
    private final Setting.Bool rainbow = new Setting.Bool("Rainbow accent", false);

    private PlayerEntity target;      // who we are tracking right now (null = nobody)
    private PlayerEntity shown;       // who is on screen (kept while fading out)
    private long lastSeen;
    private float anim, hpShown, hpTrail;
    private long lastNs;

    public TargetHud() {
        super("Target HUD", "Animated card with health, armor and gear of the targeted player.", Category.HUD);
        add(mode, range, hold, style, scale, head, armor, distance, winLose, offX, offY, tint, opacity, accent, rainbow);
        range.showIf(() -> mode.value == Mode.NEAREST);
        accent.showIf(() -> !rainbow.on());
    }

    @Override
    public void onDisable() {
        target = null;
        shown = null;
        anim = 0;
    }

    @Override
    public void onWorldChanged() {
        target = null;
        shown = null;
        anim = 0;
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) {
            target = null;
            return;
        }
        PlayerEntity found = null;
        if (mode.value == Mode.CROSSHAIR) {
            if (mc.targetedEntity instanceof PlayerEntity p && valid(p)) found = p;
        } else {
            double best = range.d() * range.d();
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (!valid(p)) continue;
                double d = mc.player.squaredDistanceTo(p);
                if (d < best) {
                    best = d;
                    found = p;
                }
            }
        }
        long now = System.currentTimeMillis();
        if (found != null) {
            target = found;
            lastSeen = now;
        } else if (target != null && (!valid(target) || now - lastSeen > hold.d() * 1000.0)) {
            target = null;
        }
    }

    private boolean valid(PlayerEntity p) {
        return p != mc.player && p.isAlive() && !p.isRemoved() && !p.isSpectator();
    }

    @Override
    public void renderHud(DrawContext ctx) {
        if (mc.player == null || mc.options.hudHidden) return;

        long nowNs = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;
        float time = (System.currentTimeMillis() % 1_000_000L) / 1000f;

        PlayerEntity t = target;
        if (t != null) {
            if (shown != t) {
                shown = t;
                hpShown = hpTrail = t.getHealth();
            }
        }
        float goal = t != null ? 1f : 0f;
        anim += Math.max(-1f, Math.min(1f, (goal - anim) * Math.min(1f, dt * 9f)));
        if (anim < 0.05f) {
            if (t == null) shown = null;
            return;
        }
        PlayerEntity p = shown;
        if (p == null) return;

        float ease = anim * anim * (3f - 2f * anim);
        TextRenderer tr = mc.textRenderer;
        int acc = rainbow.on() ? ColorUtil.hsv(time * 0.15f, 0.6f, 1f) : accent.rgb();

        // smooth health + damage trail
        float hp = p.getHealth(), max = Math.max(1f, p.getMaxHealth()), abs = p.getAbsorptionAmount();
        hpShown += (hp - hpShown) * Math.min(1f, dt * 10f);
        if (hpTrail > hpShown) hpTrail -= (hpTrail - hpShown) * Math.min(1f, dt * 2.2f);
        else hpTrail = hpShown;

        boolean items = armor.on();
        final int W = 172;
        int H = 46 + (items ? 26 : 0);
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        float px = sw / 2f + offX.i(), py = sh / 2f + offY.i();
        float sc = scale.f() * (0.9f + 0.1f * ease);

        var ms = ctx.getMatrices();
        ms.push();
        ms.translate(px + W / 2f, py + H / 2f, 0f);
        ms.scale(sc, sc, 1f);
        ms.translate(-W / 2f, -H / 2f, 0f);

        float fa = Math.max(0.05f, anim);
        Gfx.card(ctx, 0, 0, W, H, 7, tint.rgb(), opacity.f() / 100f, acc, style.value, anim, time);

        boolean full = anim > 0.5f; // skins and items cannot be faded, so they pop in once the card is mostly visible
        int tx = 10;
        if (head.on()) {
            int hx = 8, hy = 8, hs = 32;
            if (full && p instanceof AbstractClientPlayerEntity ap) {
                PlayerSkinDrawer.draw(ctx, ap.getSkinTextures(), hx, hy, hs);
                if (p.hurtTime > 0) {
                    ctx.fill(hx, hy, hx + hs, hy + hs, ColorUtil.argb(0xFF3030, 0.40f * p.hurtTime / 10f));
                }
            }
            Gfx.border(ctx, hx - 1, hy - 1, hs + 2, hs + 2, ColorUtil.argb(acc, 0.9f * fa));
            tx = hx + hs + 8;
        }
        int right = W - 8;
        int barW = right - tx;

        // name + health number
        String hpText = String.format("%.1f", hp) + (abs > 0 ? " +" + String.format("%.0f", abs) : "");
        int hpW = tr.getWidth(hpText);
        String name = tr.trimToWidth(p.getName().getString(), barW - hpW - 6);
        ctx.drawText(tr, name, tx, 9, Gfx.mulA(0xFFFFFFFF, fa), false);
        ctx.drawText(tr, hpText, right - hpW, 9, Gfx.mulA(abs > 0 ? 0xFFFFD34D : 0xFFC8C8D0, fa), false);

        // health bar: track, white damage trail, gradient fill, gold absorption line
        int by = 21, bh = 6;
        float frac = Math.max(0f, Math.min(1f, hpShown / max));
        float trailFrac = Math.max(0f, Math.min(1f, hpTrail / max));
        Gfx.roundFill(ctx, tx, by, barW, bh, 3, ColorUtil.argb(0x000000, 0.5f * fa));
        int trailW = (int) (barW * trailFrac);
        if (trailW > 0) Gfx.roundFill(ctx, tx, by, trailW, bh, 3, ColorUtil.argb(0xFFFFFF, 0.55f * fa));
        int fillW = (int) (barW * frac);
        int hc = ColorUtil.lerp(0xE05555, 0x55C46A, frac);
        if (fillW > 0) {
            Gfx.roundGradientV(ctx, tx, by, fillW, bh, 3, ColorUtil.argb(ColorUtil.lerp(hc, 0xFFFFFF, 0.35f), fa), ColorUtil.argb(hc, fa));
        }
        if (abs > 0) {
            int aw = Math.min(barW, (int) (barW * abs / max));
            ctx.fill(tx, by + bh, tx + aw, by + bh + 1, ColorUtil.argb(0xFFD34D, fa));
        }

        // info row: distance, armor value, win / lose tag
        int iy = 32;
        float ix = tx;
        if (distance.on()) {
            String d = String.format("%.1fm", mc.player.distanceTo(p));
            Gfx.text(ctx, tr, d, ix, iy, 0.85f, Gfx.mulA(0xFFB8B8C0, fa), false);
            ix += tr.getWidth(d) * 0.85f + 8;
            String a = "Armor " + p.getArmor();
            Gfx.text(ctx, tr, a, ix, iy, 0.85f, Gfx.mulA(ColorUtil.lerp(0x8A8A94, acc, 0.6f) | 0xFF000000, fa), false);
        }
        if (winLose.on()) {
            float mine = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            float theirs = p.getHealth() + p.getAbsorptionAmount();
            float diff = mine - theirs;
            String tag = diff > 0.5f ? "W" : (diff < -0.5f ? "L" : "=");
            int tc = diff > 0.5f ? 0x55C46A : (diff < -0.5f ? 0xE05555 : 0xB8B8C0);
            int tw = tr.getWidth(tag) + 8;
            int tgx = right - tw, tgy = 30;
            Gfx.roundFill(ctx, tgx, tgy, tw, 11, 4, ColorUtil.argb(tc, 0.28f * fa));
            Gfx.roundBorder(ctx, tgx, tgy, tw, 11, 4, ColorUtil.argb(tc, 0.8f * fa));
            ctx.drawText(tr, tag, tgx + 4, tgy + 2, Gfx.mulA(0xFF000000 | tc, fa), false);
        }

        // gear row
        if (items) {
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
            int sx = 8, sy = 46;
            for (EquipmentSlot s : slots) {
                Gfx.roundFill(ctx, sx, sy, 20, 20, 4, ColorUtil.argb(0xFFFFFF, 0.08f * fa));
                ItemStack st = p.getEquippedStack(s);
                if (full && !st.isEmpty()) {
                    ctx.drawItem(st, sx + 2, sy + 2);
                    ctx.drawStackOverlay(tr, st, sx + 2, sy + 2);
                }
                sx += 22;
            }
        }
        ms.pop();
    }
}
