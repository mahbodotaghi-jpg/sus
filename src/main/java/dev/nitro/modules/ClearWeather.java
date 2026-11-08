package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;

/** Clear Weather: hides rain, snow and thunder visually (and their sky darkening). The server's real weather is unchanged. */
public final class ClearWeather extends Module {
    private final Setting.Bool rain = new Setting.Bool("Hide rain and snow", true);
    private final Setting.Bool thunder = new Setting.Bool("Hide thunder", true);

    public ClearWeather() {
        super("Clear Weather", "Hides rain, snow and thunder on your screen only.", Category.RENDER);
        add(rain, thunder);
    }

    @Override
    public void onTick() {
        if (mc.world == null) return;
        if (rain.on()) mc.world.setRainGradient(0f);
        if (thunder.on()) mc.world.setThunderGradient(0f);
    }
}
