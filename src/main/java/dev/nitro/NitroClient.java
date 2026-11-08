package dev.nitro;

import dev.nitro.gui.ClickGui;
import dev.nitro.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class NitroClient implements ClientModInitializer {
    public static KeyBinding openGui;

    @Override
    public void onInitializeClient() {
        ModuleManager.INSTANCE.init();

        openGui = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.nitro.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "category.nitro"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openGui.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new ClickGui());
            }
            ModuleManager.INSTANCE.tick(mc);
        });
        WorldRenderEvents.BEFORE_ENTITIES.register(ctx -> ModuleManager.INSTANCE.renderSky(ctx));
        WorldRenderEvents.LAST.register(ctx -> ModuleManager.INSTANCE.renderWorld(ctx));
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> ModuleManager.INSTANCE.renderHud(ctx));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            Config.save();
            dev.nitro.modules.Fullbright.restore();
        });
    }
}
