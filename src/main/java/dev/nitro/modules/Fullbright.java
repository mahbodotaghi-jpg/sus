package dev.nitro.modules;

import dev.nitro.mixin.SimpleOptionAccessor;
import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import net.minecraft.client.option.SimpleOption;

/**
 * Fullbright: pushes the gamma option far above its normal maximum so the lightmap is fully bright.
 * The original gamma is restored on disable, when leaving a world and when the game closes.
 */
public final class Fullbright extends Module {
    public static Fullbright INSTANCE;

    private final Setting.Num level = new Setting.Num("Brightness", 16, 2, 16, 1);
    private Double saved;

    public Fullbright() {
        super("Fullbright", "Makes everything fully bright, caves and nights included.", Category.RENDER);
        add(level);
        INSTANCE = this;
    }

    @Override
    public void onTick() {
        if (mc.options == null) return;
        SimpleOption<Double> g = mc.options.getGamma();
        double cur = g.getValue();
        if (saved == null) saved = cur > 1.0 ? 1.0 : cur;
        if (cur != level.d()) {
            @SuppressWarnings("unchecked")
            SimpleOptionAccessor<Double> acc = (SimpleOptionAccessor<Double>) (Object) g;
            acc.nitro$setValue(level.d());
        }
    }

    @Override
    public void onDisable() {
        restoreGamma();
    }

    @Override
    public void onWorldChanged() {
        if (mc.world == null) restoreGamma();
    }

    private void restoreGamma() {
        if (saved == null || mc.options == null) return;
        SimpleOption<Double> g = mc.options.getGamma();
        if (g.getValue() > 1.0) g.setValue(saved);
        saved = null;
    }

    /** Called when the client is shutting down so options.txt never keeps an out-of-range gamma. */
    public static void restore() {
        if (INSTANCE != null) INSTANCE.restoreGamma();
    }
}
