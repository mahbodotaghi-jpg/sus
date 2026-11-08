package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.RenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** Player ESP: boxes (and optional tracers) around other players, drawn through walls. */
public final class PlayerEsp extends Module {
    public enum ColorMode {
        CUSTOM("Custom colour"), HEALTH("By health"), DISTANCE("By distance");
        private final String label;
        ColorMode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    private final Setting.Num range = new Setting.Num("Range (blocks)", 128, 8, 256, 8);
    private final Setting.Choice<ColorMode> colorMode = new Setting.Choice<>("Colour mode", ColorMode.CUSTOM, ColorMode.class);
    private final Setting.Color custom = new Setting.Color("Colour", 0xFF4D6D);
    private final Setting.Bool fill = new Setting.Bool("Fill", true);
    private final Setting.Num fillOpacity = new Setting.Num("Fill opacity", 15, 0, 50, 1);
    private final Setting.Num outlineOpacity = new Setting.Num("Outline opacity", 95, 10, 100, 5);
    private final Setting.Num lineWidth = new Setting.Num("Line width", 2, 1, 5, 0.5);
    private final Setting.Bool tracers = new Setting.Bool("Tracers", false);
    private final Setting.Bool invisible = new Setting.Bool("Include invisible players", true);
    private final Setting.Bool selfInFreecam = new Setting.Bool("Show yourself in Freecam", true);

    public PlayerEsp() {
        super("Player ESP", "Draws boxes around other players through walls.", Category.RENDER);
        add(range, colorMode, custom, fill, fillOpacity, outlineOpacity, lineWidth, tracers, invisible, selfInFreecam);
        custom.showIf(() -> colorMode.value == ColorMode.CUSTOM);
        fillOpacity.showIf(fill::on);
    }

    private int colourFor(PlayerEntity p, double dist) {
        return switch (colorMode.value) {
            case CUSTOM -> custom.rgb();
            case HEALTH -> {
                float f = Math.max(0f, Math.min(1f, p.getHealth() / Math.max(1f, p.getMaxHealth())));
                yield ColorUtil.lerp(0xE05555, 0x55E07A, f);
            }
            case DISTANCE -> {
                float f = (float) Math.max(0.0, Math.min(1.0, dist / range.d()));
                yield ColorUtil.hsv(0.33f * f, 0.85f, 1f); // red when close, green when far
            }
        };
    }

    @Override
    public void renderWorld(WorldRenderContext ctx) {
        ClientWorld w = mc.world;
        if (w == null || mc.player == null) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;

        Camera camera = ctx.camera();
        Vec3d cam = camera.getPos();
        Matrix4f m = ms.peek().getPositionMatrix();
        float td = mc.getRenderTickCounter().getTickDelta(true);
        float oa = outlineOpacity.f() / 100f, fa = fillOpacity.f() / 100f;
        double maxD = range.d();
        boolean showSelf = selfInFreecam.on() && Freecam.active();

        java.util.List<Box> boxes = new java.util.ArrayList<>();
        java.util.List<Integer> cols = new java.util.ArrayList<>();
        for (PlayerEntity p : w.getPlayers()) {
            if (p == mc.player && !showSelf) continue;
            if (!p.isAlive() || p.isSpectator()) continue;
            if (p.isInvisible() && !invisible.on()) continue;
            Vec3d lp = p.getLerpedPos(td);
            double dx = lp.x - cam.x, dy = lp.y - cam.y, dz = lp.z - cam.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > maxD) continue;
            Box b = p.getBoundingBox().offset(lp.x - p.getX(), lp.y - p.getY(), lp.z - p.getZ()).expand(0.05);
            boxes.add(b);
            cols.add(colourFor(p, dist));
        }
        if (boxes.isEmpty()) return;

        RenderUtil.begin();
        if (fill.on()) {
            BufferBuilder q = RenderUtil.quads();
            for (int i = 0; i < boxes.size(); i++) {
                Box b = boxes.get(i);
                RenderUtil.boxFill(q, m, (float) (b.minX - cam.x), (float) (b.minY - cam.y), (float) (b.minZ - cam.z),
                        (float) (b.maxX - cam.x), (float) (b.maxY - cam.y), (float) (b.maxZ - cam.z),
                        ColorUtil.argb(cols.get(i), fa));
            }
            RenderUtil.draw(q);
        }

        BufferBuilder l = RenderUtil.lines(lineWidth.f());
        Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        float sx = (float) (look.x * 0.2), sy = (float) (look.y * 0.2), sz = (float) (look.z * 0.2);
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            int col = ColorUtil.argb(cols.get(i), oa);
            float x1 = (float) (b.minX - cam.x), y1 = (float) (b.minY - cam.y), z1 = (float) (b.minZ - cam.z);
            float x2 = (float) (b.maxX - cam.x), y2 = (float) (b.maxY - cam.y), z2 = (float) (b.maxZ - cam.z);
            RenderUtil.boxLines(l, m, x1, y1, z1, x2, y2, z2, col);
            if (tracers.on()) RenderUtil.line(l, m, sx, sy, sz, (x1 + x2) / 2f, (y1 + y2) / 2f, (z1 + z2) / 2f, col);
        }
        RenderUtil.draw(l);
        RenderUtil.end();
    }
}
