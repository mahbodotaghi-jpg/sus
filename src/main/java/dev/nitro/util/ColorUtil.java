package dev.nitro.util;

public final class ColorUtil {
    private ColorUtil() {}

    /** Combine 0xRRGGBB with an alpha in 0..1 into 0xAARRGGBB. */
    public static int argb(int rgb, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    public static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        int i = (int) (h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s), q = v * (1f - f * s), t = v * (1f - (1f - f) * s);
        float r, g, b;
        switch (i % 6) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return ((int) (r * 255f) << 16) | ((int) (g * 255f) << 8) | (int) (b * 255f);
    }

    /** Light level 1..15 mapped from blue (dim) to red (bright). */
    public static int lightColor(int level) {
        float t = (Math.max(1, Math.min(15, level)) - 1) / 14f;
        return hsv(0.66f * (1f - t), 0.85f, 1f);
    }

    public static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
