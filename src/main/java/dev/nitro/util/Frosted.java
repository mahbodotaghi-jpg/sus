package dev.nitro.util;

import net.minecraft.client.gui.DrawContext;

/** Faux frosted-glass panel: translucent tint, soft sheen, light border and an accent strip. */
public final class Frosted {
    private Frosted() {}

    public static void draw(DrawContext c, int x, int y, int w, int h, int tintRgb, float opacity, int accentRgb) {
        int base = ColorUtil.argb(tintRgb, opacity);
        // body with clipped corners
        c.fill(x + 1, y, x + w - 1, y + h, base);
        c.fill(x, y + 1, x + w, y + h - 1, base);
        // sheen
        c.fillGradient(x + 1, y + 1, x + w - 1, y + h / 2, 0x30FFFFFF, 0x08FFFFFF);
        c.fillGradient(x + 1, y + h / 2, x + w - 1, y + h - 1, 0x06FFFFFF, 0x14FFFFFF);
        // border
        int hi = 0x55FFFFFF, lo = 0x22FFFFFF;
        c.fill(x + 1, y, x + w - 1, y + 1, hi);
        c.fill(x, y + 1, x + 1, y + h - 1, hi);
        c.fill(x + 1, y + h - 1, x + w - 1, y + h, lo);
        c.fill(x + w - 1, y + 1, x + w, y + h - 1, lo);
        // accent
        c.fill(x + 1, y + 3, x + 3, y + h - 3, ColorUtil.argb(accentRgb, 0.95f));
    }
}
