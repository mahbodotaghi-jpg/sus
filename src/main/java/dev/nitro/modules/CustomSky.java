package dev.nitro.modules;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.RenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Custom Sky: draws a coloured sky dome (plus optional stars, sun and moon) around the camera.
 * It is drawn right after the terrain, with depth testing but no depth writing, so it only fills pixels that
 * nothing solid has covered. Clouds, weather, particles and translucent blocks are drawn afterwards and stay
 * on top. Purely visual: the real world time is never touched.
 */
public final class CustomSky extends Module {
    public enum Mode {
        CUSTOM("Custom gradient"), SOLID("Solid colour"), SUNSET("Sunset"), SYNTHWAVE("Synthwave"),
        RAINBOW("Rainbow"), AURORA("Aurora");
        private final String label;
        Mode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum Stage {
        AFTER_TERRAIN("After terrain (clouds stay on top)"), END_OF_FRAME("End of frame (fallback)");
        private final String label;
        Stage(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    private static final int RINGS = 40, SEGS = 64, MAX_STARS = 1500;
    private static final double TAU = Math.PI * 2.0;

    private final Setting.Choice<Mode> mode = new Setting.Choice<>("Sky", Mode.CUSTOM, Mode.class);
    private final Setting.Color top = new Setting.Color("Top colour", 0x2B1B6B);
    private final Setting.Color horizon = new Setting.Color("Horizon colour", 0xFF7EB3);
    private final Setting.Color bottom = new Setting.Color("Bottom colour", 0x120A2E);
    private final Setting.Color solid = new Setting.Color("Colour", 0x4A90E2);
    private final Setting.Num speed = new Setting.Num("Animation speed", 1.0, 0.1, 5.0, 0.1);
    private final Setting.Bool followDay = new Setting.Bool("Darken at night", false);
    private final Setting.Bool lockTime = new Setting.Bool("Lock time of day", false);
    private final Setting.Num timeOfDay = new Setting.Num("Time of day", 6000, 0, 24000, 500);
    private final Setting.Bool stars = new Setting.Bool("Stars", true);
    private final Setting.Num starCount = new Setting.Num("Star count", 700, 100, 1500, 100);
    private final Setting.Bool sunMoon = new Setting.Bool("Sun and moon", true);
    private final Setting.Num bodySize = new Setting.Num("Sun / moon size", 12, 4, 40, 1);
    private final Setting.Bool overworldOnly = new Setting.Bool("Overworld only", true);
    private final Setting.Choice<Stage> stage = new Setting.Choice<>("Draw stage", Stage.AFTER_TERRAIN, Stage.class);

    private boolean drewEarly;
    private String lastDiag = "";

    private final float[] starX = new float[MAX_STARS], starY = new float[MAX_STARS], starZ = new float[MAX_STARS];
    private final double[] bas = new double[6];

    private static final double[] SIN_LAT = new double[RINGS + 1], COS_LAT = new double[RINGS + 1];
    private static final double[] SIN_AZ = new double[SEGS + 1], COS_AZ = new double[SEGS + 1];

    static {
        for (int i = 0; i <= RINGS; i++) {
            double lat = -Math.PI / 2.0 + Math.PI * i / RINGS;
            SIN_LAT[i] = Math.sin(lat);
            COS_LAT[i] = Math.cos(lat);
        }
        for (int j = 0; j <= SEGS; j++) {
            double az = TAU * j / SEGS;
            SIN_AZ[j] = Math.sin(az);
            COS_AZ[j] = Math.cos(az);
        }
    }

    public CustomSky() {
        super("Custom Sky", "Replaces the sky with a gradient, rainbow, aurora or preset, with stars, sun and moon.", Category.RENDER);
        add(mode, top, horizon, bottom, solid, speed, followDay, lockTime, timeOfDay, stars, starCount, sunMoon, bodySize, overworldOnly, stage);
        top.showIf(() -> mode.value == Mode.CUSTOM);
        horizon.showIf(() -> mode.value == Mode.CUSTOM);
        bottom.showIf(() -> mode.value == Mode.CUSTOM);
        solid.showIf(() -> mode.value == Mode.SOLID);
        speed.showIf(() -> mode.value == Mode.RAINBOW || mode.value == Mode.AURORA);
        timeOfDay.showIf(lockTime::on);
        starCount.showIf(stars::on);
        bodySize.showIf(sunMoon::on);

        Random r = new Random(1337L);
        for (int i = 0; i < MAX_STARS; i++) {
            double y = r.nextDouble() * 2.0 - 1.0;
            double az = r.nextDouble() * TAU;
            double rr = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            starX[i] = (float) (rr * Math.cos(az));
            starY[i] = (float) y;
            starZ[i] = (float) (rr * Math.sin(az));
        }
    }

    // ---- colours ---------------------------------------------------------------------------------------------

    private static int grad(int top, int hor, int bot, double el) {
        if (el >= 0) return ColorUtil.lerp(hor, top, (float) Math.pow(el, 0.55));
        return ColorUtil.lerp(hor, bot, (float) Math.pow(-el, 0.55));
    }

    private static int aurora(int base, double az, double el, float t) {
        double wave = Math.sin(az * 3.0 + t * 0.6 + Math.sin(az * 7.0 - t * 0.4) * 0.8) * 0.5 + 0.5;
        double d = (el - 0.35) / 0.22;
        double band = Math.exp(-d * d);
        float k = (float) (wave * band * 0.9);
        float mix = (float) Math.max(0.0, Math.min(1.0, (el - 0.15) / 0.5));
        return ColorUtil.lerp(base, ColorUtil.lerp(0x2BFF88, 0xB04DFF, mix), k);
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    // ---- rendering -------------------------------------------------------------------------------------------

    private void diag(String msg) {
        if (msg.equals(lastDiag)) return;
        lastDiag = msg;
        System.out.println("[NITRO] Custom Sky: " + msg);
    }

    /** Right after the terrain (default). */
    @Override
    public void renderSky(WorldRenderContext ctx) {
        drewEarly = false;
        if (stage.value != Stage.AFTER_TERRAIN) return;
        drewEarly = draw(ctx, "after terrain");
    }

    /** End of the frame: fallback when the early event did not draw, or when chosen in the settings. */
    @Override
    public void renderSkyLate(WorldRenderContext ctx) {
        boolean need = stage.value == Stage.END_OF_FRAME || !drewEarly;
        drewEarly = false;
        if (need) draw(ctx, "end of frame");
    }

    private boolean draw(WorldRenderContext ctx, String where) {
        ClientWorld w = mc.world;
        if (w == null || mc.player == null) return false;
        if (overworldOnly.on() && w.getRegistryKey() != World.OVERWORLD) {
            diag("skipped: not in the Overworld (turn off 'Overworld only' to use it elsewhere)");
            return false;
        }
        // No custom sky while under water, in lava or in powder snow.
        if (!ctx.camera().getSubmersionType().name().equals("NONE")) {
            diag("skipped: camera is submerged");
            return false;
        }
        MatrixStack ms = ctx.matrixStack();
        Matrix4f m = ms != null ? ms.peek().getPositionMatrix() : new Matrix4f();

        float td = mc.getRenderTickCounter().getTickDelta(true);
        float t = (float) ((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000f;

        double tod = lockTime.on() ? timeOfDay.d() : (((w.getTimeOfDay() + td) % 24000.0) + 24000.0) % 24000.0;
        double phi = tod / 24000.0 * TAU;
        double sunX = Math.cos(phi), sunY = Math.sin(phi);
        float day = followDay.on() ? smooth(-0.2f, 0.25f, (float) sunY) : 1f;

        int vd = Math.max(2, mc.options.getViewDistance().getValue());
        float radius = vd * 32f;

        int tp, hz, bt;
        switch (mode.value) {
            case SUNSET -> { tp = 0x1E2A78; hz = 0xFF8A3D; bt = 0x3A1F4D; }
            case SYNTHWAVE -> { tp = 0x0B0221; hz = 0xFF2E97; bt = 0x2A0A4A; }
            case AURORA -> { tp = 0x050B1F; hz = 0x0B2A3A; bt = 0x03060F; }
            default -> { tp = top.rgb(); hz = horizon.rgb(); bt = bottom.rgb(); }
        }

        diag("drawing " + where + ", radius " + (int) radius + (ms == null ? ", no matrix stack" : ""));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        // Dome: per-vertex colours.
        int[] col = new int[(RINGS + 1) * (SEGS + 1)];
        for (int i = 0; i <= RINGS; i++) {
            double el = SIN_LAT[i];
            for (int j = 0; j <= SEGS; j++) {
                double az = TAU * j / SEGS;
                int c = switch (mode.value) {
                    case SOLID -> solid.rgb();
                    case RAINBOW -> ColorUtil.hsv((float) (az / TAU + el * 0.15 + t * speed.f() * 0.04), 0.65f, 0.95f);
                    case AURORA -> aurora(grad(tp, hz, bt, el), az, el, t);
                    default -> grad(tp, hz, bt, el);
                };
                if (day < 1f) c = ColorUtil.lerp(ColorUtil.lerp(c, 0x000000, 0.88f), c, day);
                col[i * (SEGS + 1) + j] = 0xFF000000 | c;
            }
        }
        BufferBuilder q = RenderUtil.quads();
        for (int i = 0; i < RINGS; i++) {
            for (int j = 0; j < SEGS; j++) {
                vert(q, m, i, j, radius, col);
                vert(q, m, i, j + 1, radius, col);
                vert(q, m, i + 1, j + 1, radius, col);
                vert(q, m, i + 1, j, radius, col);
            }
        }
        RenderUtil.draw(q);

        // Stars.
        float starA = followDay.on() ? 1f - day : 1f;
        if (stars.on() && starA > 0.02f) {
            double cp = Math.cos(phi), sp = Math.sin(phi);
            int n = starCount.i();
            BufferBuilder sb = RenderUtil.quads();
            for (int i = 0; i < n; i++) {
                double dx = starX[i] * cp - starY[i] * sp;
                double dy = starX[i] * sp + starY[i] * cp;
                double dz = starZ[i];
                if (dy < -0.05) continue;
                float tw = 0.55f + 0.45f * (float) Math.sin(t * 2.0 + i * 1.7);
                int argb = ColorUtil.argb(0xFFFFFF, tw * starA);
                basis(dx, dy, dz);
                double s = radius * 0.0035 * (0.7 + (i % 5) * 0.15);
                double px = dx * radius * 0.985, py = dy * radius * 0.985, pz = dz * radius * 0.985;
                for (int k = 0; k < 4; k++) {
                    double su = (k == 0 || k == 3) ? -s : s;
                    double sv = (k < 2) ? -s : s;
                    sb.vertex(m, (float) (px + bas[0] * su + bas[3] * sv), (float) (py + bas[1] * su + bas[4] * sv),
                            (float) (pz + bas[2] * su + bas[5] * sv)).color(argb);
                }
            }
            RenderUtil.draw(sb);
        }

        // Sun and moon.
        if (sunMoon.on()) {
            BufferBuilder tb = RenderUtil.tris();
            if (sunY > -0.15) disc(tb, m, sunX, sunY, 0.0, radius, bodySize.f(), 0xFFE27A, 0.45f);
            if (-sunY > -0.15) disc(tb, m, -sunX, -sunY, 0.0, radius, bodySize.f() * 0.8f, 0xDDE6FF, 0.25f);
            RenderUtil.draw(tb);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        return true;
    }

    private static void vert(BufferBuilder b, Matrix4f m, int i, int j, float r, int[] col) {
        float x = (float) (COS_LAT[i] * COS_AZ[j] * r);
        float y = (float) (SIN_LAT[i] * r);
        float z = (float) (COS_LAT[i] * SIN_AZ[j] * r);
        b.vertex(m, x, y, z).color(col[i * (SEGS + 1) + j]);
    }

    /** Fills {@link #bas} with two unit vectors u (0..2) and v (3..5) perpendicular to direction d and to each other. */
    private void basis(double dx, double dy, double dz) {
        double rx = Math.abs(dy) > 0.95 ? 1 : 0, ry = Math.abs(dy) > 0.95 ? 0 : 1;
        double ux = ry * dz, uy = -rx * dz, uz = rx * dy - ry * dx;
        double len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (len < 1e-9) {
            ux = 1; uy = 0; uz = 0; len = 1;
        }
        ux /= len; uy /= len; uz /= len;
        bas[0] = ux; bas[1] = uy; bas[2] = uz;
        bas[3] = dy * uz - dz * uy;
        bas[4] = dz * ux - dx * uz;
        bas[5] = dx * uy - dy * ux;
    }

    /** Solid disc with a soft halo, centred on direction d, facing the camera. */
    private void disc(BufferBuilder b, Matrix4f m, double dx, double dy, double dz, float radius, float angDeg, int rgb, float glow) {
        basis(dx, dy, dz);
        double dist = radius * 0.96;
        double core = dist * Math.tan(Math.toRadians(angDeg / 2.0));
        double halo = core * 3.2;
        double cx = dx * dist, cy = dy * dist, cz = dz * dist;
        int solidC = ColorUtil.argb(rgb, 1f), glowC = ColorUtil.argb(rgb, glow), clear = ColorUtil.argb(rgb, 0f);
        int n = 28;
        for (int k = 0; k < n; k++) {
            double a0 = TAU * k / n, a1 = TAU * (k + 1) / n;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            // solid core
            b.vertex(m, (float) cx, (float) cy, (float) cz).color(solidC);
            ring(b, m, cx, cy, cz, core, c0, s0, solidC);
            ring(b, m, cx, cy, cz, core, c1, s1, solidC);
            // halo strip
            ring(b, m, cx, cy, cz, core, c0, s0, glowC);
            ring(b, m, cx, cy, cz, halo, c0, s0, clear);
            ring(b, m, cx, cy, cz, halo, c1, s1, clear);
            ring(b, m, cx, cy, cz, core, c0, s0, glowC);
            ring(b, m, cx, cy, cz, halo, c1, s1, clear);
            ring(b, m, cx, cy, cz, core, c1, s1, glowC);
        }
    }

    private void ring(BufferBuilder b, Matrix4f m, double cx, double cy, double cz, double rad, double c, double s, int argb) {
        b.vertex(m,
                (float) (cx + (bas[0] * c + bas[3] * s) * rad),
                (float) (cy + (bas[1] * c + bas[4] * s) * rad),
                (float) (cz + (bas[2] * c + bas[5] * s) * rad)).color(argb);
    }
}
