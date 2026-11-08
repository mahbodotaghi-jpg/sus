package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.Anchor;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.Gfx;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayDeque;
import java.util.Deque;

/** Keystrokes: shows WASD, space and the mouse buttons (with clicks per second) as they are pressed. */
public final class Keystrokes extends Module {
    private final Setting.Bool space = new Setting.Bool("Space bar", true);
    private final Setting.Bool mouse = new Setting.Bool("Mouse buttons + CPS", true);
    private final Setting.Choice<Anchor> anchor = new Setting.Choice<>("Position", Anchor.BOTTOM_LEFT, Anchor.class);
    private final Setting.Num scale = new Setting.Num("Scale", 1.0, 0.6, 2.0, 0.05);
    private final Setting.Num offX = new Setting.Num("X offset", 8, 0, 300, 1);
    private final Setting.Num offY = new Setting.Num("Y offset", 40, 0, 300, 1);
    private final Setting.Color tint = new Setting.Color("Key colour", 0x20242C);
    private final Setting.Num opacity = new Setting.Num("Key opacity", 60, 20, 95, 5);
    private final Setting.Color pressed = new Setting.Color("Pressed colour", 0x6EA8FF);

    private final Deque<Long> lmb = new ArrayDeque<>(), rmb = new ArrayDeque<>();
    private boolean lastL, lastR;

    public Keystrokes() {
        super("Keystrokes", "Shows pressed movement keys and mouse buttons with CPS.", Category.HUD);
        add(space, mouse, anchor, scale, offX, offY, tint, opacity, pressed);
    }

    private static void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 1000L) q.pollFirst();
    }

    private void key(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h, String label, boolean down) {
        int base = ColorUtil.argb(tint.rgb(), opacity.f() / 100f);
        int on = ColorUtil.argb(pressed.rgb(), 0.85f);
        Gfx.roundFill(ctx, x, y, w, h, 3, down ? on : base);
        Gfx.roundBorder(ctx, x, y, w, h, 3, Gfx.mulA(0x55FFFFFF, down ? 1f : 0.6f));
        int col = down ? 0xFF101216 : 0xFFE8EAF0;
        ctx.drawText(tr, label, x + (w - tr.getWidth(label)) / 2, y + (h - 8) / 2, col, false);
    }

    private static String label(KeyBinding k) {
        String s = k.getBoundKeyLocalizedText().getString().toUpperCase();
        return s.length() > 3 ? s.substring(0, 3) : s;
    }

    @Override
    public void renderHud(DrawContext ctx) {
        if (mc.player == null || mc.options.hudHidden) return;
        long now = System.currentTimeMillis();
        boolean l = mc.options.attackKey.isPressed(), r = mc.options.useKey.isPressed();
        if (l && !lastL) lmb.addLast(now);
        if (r && !lastR) rmb.addLast(now);
        lastL = l;
        lastR = r;
        prune(lmb, now);
        prune(rmb, now);

        int k = 22, g = 2;
        int w = k * 3 + g * 2;
        int h = k * 2 + g;
        if (space.on()) h += g + 14;
        if (mouse.on()) h += g + 26;

        float sc = scale.f();
        int x = anchor.value.x(ctx.getScaledWindowWidth(), (int) (w * sc), offX.i());
        int y = anchor.value.y(ctx.getScaledWindowHeight(), (int) (h * sc), offY.i());
        TextRenderer tr = mc.textRenderer;

        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(x, y, 0f);
        ms.scale(sc, sc, 1f);
        int cy = 0;
        key(ctx, tr, k + g, cy, k, k, label(mc.options.forwardKey), mc.options.forwardKey.isPressed());
        cy += k + g;
        key(ctx, tr, 0, cy, k, k, label(mc.options.leftKey), mc.options.leftKey.isPressed());
        key(ctx, tr, k + g, cy, k, k, label(mc.options.backKey), mc.options.backKey.isPressed());
        key(ctx, tr, 2 * (k + g), cy, k, k, label(mc.options.rightKey), mc.options.rightKey.isPressed());
        cy += k;
        if (space.on()) {
            cy += g;
            key(ctx, tr, 0, cy, w, 14, "SPACE", mc.options.jumpKey.isPressed());
            cy += 14;
        }
        if (mouse.on()) {
            cy += g;
            int bw = (w - g) / 2;
            mouseKey(ctx, tr, 0, cy, bw, 26, "LMB", lmb.size(), l);
            mouseKey(ctx, tr, bw + g, cy, bw, 26, "RMB", rmb.size(), r);
        }
        ms.pop();
    }

    private void mouseKey(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h, String name, int cps, boolean down) {
        int base = ColorUtil.argb(tint.rgb(), opacity.f() / 100f);
        int on = ColorUtil.argb(pressed.rgb(), 0.85f);
        Gfx.roundFill(ctx, x, y, w, h, 3, down ? on : base);
        Gfx.roundBorder(ctx, x, y, w, h, 3, Gfx.mulA(0x55FFFFFF, down ? 1f : 0.6f));
        int col = down ? 0xFF101216 : 0xFFE8EAF0;
        int dim = down ? 0xFF2A2E36 : 0xFF9AA0AA;
        ctx.drawText(tr, name, x + (w - tr.getWidth(name)) / 2, y + 4, col, false);
        String c = cps + " CPS";
        Gfx.text(ctx, tr, c, x + (w - tr.getWidth(c) * 0.8f) / 2f, y + 15, 0.8f, dim, false);
    }
}
