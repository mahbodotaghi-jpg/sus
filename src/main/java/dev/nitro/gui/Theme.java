package dev.nitro.gui;

import com.google.gson.JsonObject;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;

import java.util.List;

/** ClickGUI colour themes. The selected preset and its options are saved in config/nitro.json under "theme". */
public final class Theme {
    private Theme() {}

    public enum Preset {
        //     label       blurb                       accent1   accent2   winTop    winBot    backTop   backBot   text      dim       track     faint
        NITRO("Nitro", "Violet to cyan (default)", 0x8B5CF6, 0x22D3EE, 0x1B1729, 0x0B0A13, 0x05040C, 0x0B0618, 0xF1F0F7, 0x8E8AA3, 0x3A3650, 0x5F5C73),
        OCEAN("Ocean", "Cool blue and teal", 0x38BDF8, 0x2DD4BF, 0x0F1C2B, 0x08121C, 0x040A12, 0x06131F, 0xEAF6FF, 0x7F9BB3, 0x2A3F55, 0x50677D),
        SUNSET("Sunset", "Rose into amber", 0xFB7185, 0xFBBF24, 0x261521, 0x140A12, 0x0C0508, 0x1A0A10, 0xFFF1F0, 0xB0899A, 0x4A2E3E, 0x7A5566),
        EMERALD("Emerald", "Green and lime", 0x34D399, 0xA3E635, 0x12211A, 0x08120D, 0x030A06, 0x061510, 0xEEFFF6, 0x7FA394, 0x2A4A3C, 0x52705F),
        CRIMSON("Crimson", "Red and orange", 0xEF4444, 0xF97316, 0x231416, 0x0F0A0B, 0x0A0405, 0x160708, 0xFFF0F0, 0xA38888, 0x4A2A2A, 0x755555),
        SAKURA("Sakura", "Pink and lavender", 0xF472B6, 0xC4B5FD, 0x261A2B, 0x140C1B, 0x0B0610, 0x180D1E, 0xFFF0FA, 0xA98DB5, 0x45324F, 0x765E80),
        MONO("Midnight", "Greyscale, no colour", 0xE5E7EB, 0x94A3B8, 0x17181C, 0x0A0B0D, 0x030304, 0x0A0B0F, 0xF3F4F6, 0x8B8F98, 0x33353C, 0x5A5D66),
        CUSTOM("Custom", "Your own two accent colours", 0x8B5CF6, 0x22D3EE, 0, 0, 0, 0, 0xF1F0F7, 0x8F8E9C, 0x3A3948, 0x5F5E6B);

        public final String label, blurb;
        final int a1, a2, winTop, winBot, backTop, backBot, text, dim, track, faint;

        Preset(String label, String blurb, int a1, int a2, int winTop, int winBot, int backTop, int backBot,
               int text, int dim, int track, int faint) {
            this.label = label;
            this.blurb = blurb;
            this.a1 = a1;
            this.a2 = a2;
            this.winTop = winTop;
            this.winBot = winBot;
            this.backTop = backTop;
            this.backBot = backBot;
            this.text = text;
            this.dim = dim;
            this.track = track;
            this.faint = faint;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final Setting.Choice<Preset> PRESET = new Setting.Choice<>("Preset", Preset.NITRO, Preset.class);
    public static final Setting.Bool ANIMATED = new Setting.Bool("Animated accent", true);
    public static final Setting.Color CUSTOM_A1 = new Setting.Color("Custom accent 1", 0xFF5CAA);
    public static final Setting.Color CUSTOM_A2 = new Setting.Color("Custom accent 2", 0xFFD166);

    private static final List<Setting<?>> ALL = List.<Setting<?>>of(PRESET, ANIMATED, CUSTOM_A1, CUSTOM_A2);
    private static final List<Setting<?>> SHOWN = List.<Setting<?>>of(ANIMATED, CUSTOM_A1, CUSTOM_A2);

    static {
        CUSTOM_A1.showIf(Theme::isCustom);
        CUSTOM_A2.showIf(Theme::isCustom);
    }

    /** Settings shown in the right-hand pane of the Themes tab. */
    public static List<Setting<?>> settings() {
        return SHOWN;
    }

    public static boolean isCustom() {
        return PRESET.value == Preset.CUSTOM;
    }

    private static Preset p() {
        return PRESET.value;
    }

    public static int a1() {
        return isCustom() ? CUSTOM_A1.rgb() : p().a1;
    }

    public static int a2() {
        return isCustom() ? CUSTOM_A2.rgb() : p().a2;
    }

    private static int customWinTop() {
        return ColorUtil.lerp(0x17171D, CUSTOM_A1.rgb(), 0.10f);
    }

    public static int winTop() {
        return 0xF2000000 | (isCustom() ? customWinTop() : p().winTop);
    }

    public static int winBot() {
        return 0xF2000000 | (isCustom() ? ColorUtil.lerp(0x09090D, CUSTOM_A1.rgb(), 0.05f) : p().winBot);
    }

    public static int backTop() {
        return 0xB0000000 | (isCustom() ? ColorUtil.lerp(0x040408, CUSTOM_A1.rgb(), 0.04f) : p().backTop);
    }

    public static int backBot() {
        return 0xD0000000 | (isCustom() ? ColorUtil.lerp(0x07070C, CUSTOM_A2.rgb(), 0.06f) : p().backBot);
    }

    public static int text() {
        return 0xFF000000 | p().text;
    }

    public static int dim() {
        return 0xFF000000 | p().dim;
    }

    public static int track() {
        return 0xFF000000 | p().track;
    }

    public static int faint() {
        return 0xFF000000 | p().faint;
    }

    /** Between dim and text: used for idle labels. */
    public static int soft() {
        return 0xFF000000 | ColorUtil.lerp(p().dim, p().text, 0.72f);
    }

    /** {accent1, accent2, window colour} of any preset, for the preview dots in the list. */
    public static int[] preview(Preset pr) {
        if (pr == Preset.CUSTOM) return new int[]{CUSTOM_A1.rgb(), CUSTOM_A2.rgb(), customWinTop()};
        return new int[]{pr.a1, pr.a2, pr.winTop};
    }

    public static JsonObject save() {
        JsonObject o = new JsonObject();
        for (Setting<?> s : ALL) o.add(s.name, s.save());
        return o;
    }

    public static void load(JsonObject o) {
        for (Setting<?> s : ALL) {
            if (!o.has(s.name)) continue;
            try {
                s.load(o.get(s.name));
            } catch (Exception ignored) {
            }
        }
    }
}
