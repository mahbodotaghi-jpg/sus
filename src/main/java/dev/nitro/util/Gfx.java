package dev.nitro.util;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Small 2D drawing helpers shared by the HUD modules: rounded rectangles, glow, panels, scaled text. */
public final class Gfx {
    private Gfx() {}

    public enum PanelStyle {
        GLASS("Glass"), NEON("Neon"), FLAT("Flat");
        private final String label;
        PanelStyle(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    /** Multiply the alpha channel of an 0xAARRGGBB colour. */
    public static int mulA(int argb, float m) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, m)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int lerpArgb(int a, int b, float t) {
        int aa = (int) (((a >>> 24) & 0xFF) * (1 - t) + ((b >>> 24) & 0xFF) * t);
        return (aa << 24) | (ColorUtil.lerp(a & 0xFFFFFF, b & 0xFFFFFF, t) & 0xFFFFFF);
    }

    /** Horizontal inset of corner row i (0 = outermost row) for a corner radius r. */
    private static int inset(int r, int i) {
        double dy = r - i - 0.5;
        return r - (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    private static int clampR(int r, int w, int h) {
        return Math.max(0, Math.min(r, Math.min(w, h) / 2));
    }

    public static void roundFill(DrawContext c, int x, int y, int w, int h, int r, int argb) {
        r = clampR(r, w, h);
        if (r == 0) {
            c.fill(x, y, x + w, y + h, argb);
            return;
        }
        c.fill(x, y + r, x + w, y + h - r, argb);
        for (int i = 0; i < r; i++) {
            int in = inset(r, i);
            c.fill(x + in, y + i, x + w - in, y + i + 1, argb);
            c.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, argb);
        }
    }

    /** Rounded rectangle with a vertical colour gradient (one fill per row, so translucency never double-blends). */
    public static void roundGradientV(DrawContext c, int x, int y, int w, int h, int r, int topArgb, int bottomArgb) {
        r = clampR(r, w, h);
        for (int j = 0; j < h; j++) {
            int in = j < r ? inset(r, j) : (j >= h - r ? inset(r, h - 1 - j) : 0);
            float t = h <= 1 ? 0f : j / (float) (h - 1);
            c.fill(x + in, y + j, x + w - in, y + j + 1, lerpArgb(topArgb, bottomArgb, t));
        }
    }

    /** 1px rounded outline. */
    public static void roundBorder(DrawContext c, int x, int y, int w, int h, int r, int argb) {
        r = clampR(r, w, h);
        if (r == 0) {
            border(c, x, y, w, h, argb);
            return;
        }
        c.fill(x + r, y, x + w - r, y + 1, argb);
        c.fill(x + r, y + h - 1, x + w - r, y + h, argb);
        c.fill(x, y + r, x + 1, y + h - r, argb);
        c.fill(x + w - 1, y + r, x + w, y + h - r, argb);
        for (int i = 0; i < r; i++) {
            int a = inset(r, i);
            int b = i == 0 ? r : Math.max(a + 1, inset(r, i - 1));
            if (b <= a) b = a + 1;
            c.fill(x + a, y + i, x + b, y + i + 1, argb);
            c.fill(x + w - b, y + i, x + w - a, y + i + 1, argb);
            c.fill(x + a, y + h - 1 - i, x + b, y + h - i, argb);
            c.fill(x + w - b, y + h - 1 - i, x + w - a, y + h - i, argb);
        }
    }

    /** Plain 1px square outline. */
    public static void border(DrawContext c, int x, int y, int w, int h, int argb) {
        c.fill(x, y, x + w, y + 1, argb);
        c.fill(x, y + h - 1, x + w, y + h, argb);
        c.fill(x, y + 1, x + 1, y + h - 1, argb);
        c.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
    }

    /** Soft glow made of a few fading outlines around a rounded rectangle. */
    public static void glow(DrawContext c, int x, int y, int w, int h, int r, int rgb, float alpha) {
        for (int k = 1; k <= 3; k++) {
            roundBorder(c, x - k, y - k, w + 2 * k, h + 2 * k, r + k, ColorUtil.argb(rgb, alpha / (k + 0.6f)));
        }
    }

    /** Panel background in one of three styles. alpha fades the whole thing, time (seconds) drives the neon pulse. */
    public static void card(DrawContext c, int x, int y, int w, int h, int r, int tint, float opacity, int accent,
                            PanelStyle style, float alpha, float time) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 2.2f);
        switch (style) {
            case GLASS -> {
                int top = ColorUtil.lerp(tint, 0xFFFFFF, 0.14f);
                roundGradientV(c, x, y, w, h, r, ColorUtil.argb(top, opacity * alpha), ColorUtil.argb(tint, opacity * alpha));
                roundBorder(c, x, y, w, h, r, mulA(0x55FFFFFF, alpha));
                c.fill(x + r, y + 1, x + w - r, y + 2, mulA(0x40FFFFFF, alpha));
                c.fill(x + r + 2, y + h - 2, x + w - r - 2, y + h - 1, ColorUtil.argb(accent, 0.85f * alpha));
            }
            case NEON -> {
                int dark = ColorUtil.lerp(tint, 0x000000, 0.55f);
                float body = Math.min(1f, opacity + 0.2f) * alpha;
                glow(c, x, y, w, h, r, accent, (0.30f + 0.25f * pulse) * alpha);
                roundGradientV(c, x, y, w, h, r, ColorUtil.argb(dark, body), ColorUtil.argb(ColorUtil.lerp(dark, accent, 0.14f), body));
                roundBorder(c, x, y, w, h, r, ColorUtil.argb(accent, (0.65f + 0.35f * pulse) * alpha));
            }
            case FLAT -> {
                roundFill(c, x, y, w, h, r, ColorUtil.argb(tint, opacity * alpha));
                c.fill(x + 3, y + 5, x + 5, y + h - 5, ColorUtil.argb(accent, 0.95f * alpha));
            }
        }
    }

    /** Text at a fractional position and scale (uses the matrix stack, so do not combine with scissors inside a scaled stack). */
    public static void text(DrawContext c, TextRenderer tr, String s, float x, float y, float scale, int argb, boolean shadow) {
        var ms = c.getMatrices();
        ms.push();
        ms.translate(x, y, 0f);
        ms.scale(scale, scale, 1f);
        c.drawText(tr, s, 0, 0, argb, shadow);
        ms.pop();
    }
}
