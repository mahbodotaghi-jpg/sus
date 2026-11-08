package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import net.minecraft.client.input.Input;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.math.Vec3d;

/**
 * Freecam: the camera detaches from the player and flies freely. The real player stays where it is:
 * its input is replaced with an empty one (restored on disable) and mouse look is redirected to the camera
 * by {@code EntityMixin}. The camera pose itself is applied by {@code CameraMixin}.
 */
public final class Freecam extends Module {
    public static Freecam INSTANCE;

    /** Input that never moves the player. */
    private static final class NullInput extends Input {
    }

    public final Setting.Num speed = new Setting.Num("Speed", 0.6, 0.1, 5.0, 0.1);
    public final Setting.Num boost = new Setting.Num("Sprint boost", 2.0, 1.0, 5.0, 0.5);
    public final Setting.Bool showPlayer = new Setting.Bool("Show player", true);
    public final Setting.Bool noCulling = new Setting.Bool("Disable chunk culling", true);
    public final Setting.Bool offOnDamage = new Setting.Bool("Disable on damage", true);

    public double x, y, z, px, py, pz;
    public float yaw, pitch;

    private Input savedInput;
    private boolean savedCulling;
    private boolean ready;

    public Freecam() {
        super("Freecam", "Moves the camera independently from the player.", Category.RENDER);
        add(speed, boost, showPlayer, noCulling, offOnDamage);
        INSTANCE = this;
    }

    /** True while the camera should be overridden. */
    public static boolean active() {
        return INSTANCE != null && INSTANCE.enabled && INSTANCE.ready;
    }

    @Override
    public void onEnable() {
        // Config.load() can enable modules at startup, before any world exists.
        if (mc.player == null || mc.world == null) {
            enabled = false;
            return;
        }
        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        x = px = cam.x;
        y = py = cam.y;
        z = pz = cam.z;
        yaw = mc.player.getYaw();
        pitch = mc.player.getPitch();

        savedInput = mc.player.input;
        mc.player.input = new NullInput();
        savedCulling = mc.chunkCullingEnabled;
        if (noCulling.on()) mc.chunkCullingEnabled = false;
        ready = true;
    }

    @Override
    public void onDisable() {
        ready = false;
        if (mc.player != null && savedInput != null && mc.player.input instanceof NullInput) {
            mc.player.input = savedInput;
        }
        savedInput = null;
        mc.chunkCullingEnabled = savedCulling;
    }

    @Override
    public void onWorldChanged() {
        // The old player (and its input) is gone; nothing to restore. Just switch off.
        if (enabled) {
            savedInput = null;
            setEnabled(false);
        }
    }

    /** Mouse look, redirected from Entity#changeLookDirection (same scaling as vanilla). */
    public void look(double dx, double dy) {
        yaw += (float) dx * 0.15f;
        pitch = Math.max(-90f, Math.min(90f, pitch + (float) dy * 0.15f));
    }

    public double x(float t) { return px + (x - px) * t; }

    public double y(float t) { return py + (y - py) * t; }

    public double z(float t) { return pz + (z - pz) * t; }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        if (!mc.player.isAlive() || (offOnDamage.on() && mc.player.hurtTime > 0)) {
            setEnabled(false);
            return;
        }
        // keep the real player from sprinting/moving if something re-created the input
        if (!(mc.player.input instanceof NullInput)) {
            savedInput = mc.player.input;
            mc.player.input = new NullInput();
        }

        px = x;
        py = y;
        pz = z;

        GameOptions o = mc.options;
        float fwd = (o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0);
        float left = (o.leftKey.isPressed() ? 1 : 0) - (o.rightKey.isPressed() ? 1 : 0);
        float up = (o.jumpKey.isPressed() ? 1 : 0) - (o.sneakKey.isPressed() ? 1 : 0);

        double yr = Math.toRadians(yaw);
        double sin = Math.sin(yr), cos = Math.cos(yr);
        double dx = -sin * fwd + cos * left;
        double dz = cos * fwd + sin * left;
        double hl = Math.sqrt(dx * dx + dz * dz);
        if (hl > 1e-6) {
            dx /= hl;
            dz /= hl;
        }
        double s = speed.d() * (o.sprintKey.isPressed() ? boost.d() : 1.0);
        x += dx * s;
        z += dz * s;
        y += up * s;
    }
}
