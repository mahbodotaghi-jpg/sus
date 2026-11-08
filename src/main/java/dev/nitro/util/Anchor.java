package dev.nitro.util;

/** Screen corner a HUD panel is pinned to. */
public enum Anchor {
    TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right");

    private final String label;

    Anchor(String l) {
        label = l;
    }

    public int x(int screenW, int w, int margin) {
        return (this == TOP_LEFT || this == BOTTOM_LEFT) ? margin : screenW - w - margin;
    }

    public int y(int screenH, int h, int margin) {
        return (this == TOP_LEFT || this == TOP_RIGHT) ? margin : screenH - h - margin;
    }

    @Override
    public String toString() {
        return label;
    }
}
