package dev.nitro.module;

import dev.nitro.settings.Setting;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    public enum Category {
        RENDER("Render", "R"),
        WORLD("World", "W"),
        HUD("HUD", "H"),
        COMBAT("Combat", "C");

        public final String label, glyph;

        Category(String label, String glyph) {
            this.label = label;
            this.glyph = glyph;
        }
    }

    public final String name, description;
    public final Category category;
    public boolean enabled, favorite;
    public int key = -1;
    public boolean keyWasDown;
    public final List<Setting<?>> settings = new ArrayList<>();
    protected final MinecraftClient mc = MinecraftClient.getInstance();

    protected Module(String name, String description, Category category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    protected void add(Setting<?>... s) {
        for (Setting<?> x : s) settings.add(x);
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) onEnable();
        else onDisable();
    }

    public void onEnable() {}

    public void onDisable() {}

    public void onTick() {}

    /** Called when the world object changes (join, leave, dimension change). Must clear all results. */
    public void onWorldChanged() {}

    public void renderWorld(WorldRenderContext ctx) {}

    /** Called right after the terrain is drawn, before entities (used by Custom Sky). */
    public void renderSky(WorldRenderContext ctx) {}

    /** Called at the very end of world rendering, before renderWorld (fallback for Custom Sky). */
    public void renderSkyLate(WorldRenderContext ctx) {}

    public void renderHud(DrawContext ctx) {}
}
