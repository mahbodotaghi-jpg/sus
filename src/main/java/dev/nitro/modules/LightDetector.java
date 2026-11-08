package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.RenderUtil;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Light Debug: highlights loaded block light below Y=0 that is brighter than the sky light there.
 * Scanning is incremental (a few chunks per tick, nearest first) and results are dropped on disable / world change.
 */
public final class LightDetector extends Module {
    public enum ColorMode {
        LIGHT("By light level"), CUSTOM("Custom colour");
        private final String label;
        ColorMode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    private final Setting.Num radius = new Setting.Num("Render radius", 6, 1, 16, 1);
    private final Setting.Num minLight = new Setting.Num("Minimum light", 4, 1, 15, 1);
    private final Setting.Choice<ColorMode> colorMode = new Setting.Choice<>("Color mode", ColorMode.LIGHT, ColorMode.class);
    private final Setting.Color color = new Setting.Color("Color", 0xFFD34D);
    private final Setting.Num opacity = new Setting.Num("Opacity", 45, 5, 100, 5);
    private final Setting.Bool fill = new Setting.Bool("Fill", true);
    private final Setting.Bool denoise = new Setting.Bool("Denoise filter", true);
    private final Setting.Num denoiseMin = new Setting.Num("Denoise neighbours", 2, 1, 6, 1);
    private final Setting.Num maxCells = new Setting.Num("Max drawn cells", 6000, 500, 20000, 500);
    private final Setting.Num speed = new Setting.Num("Scan speed", 2, 1, 6, 1);

    private static final class Data {
        final int cx, cz;
        final LongArrayList pos = new LongArrayList();
        final ByteArrayList lvl = new ByteArrayList();
        Data(int cx, int cz) { this.cx = cx; this.cz = cz; }
    }

    private final Map<Long, Data> results = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private List<Data> order = new ArrayList<>();
    private int tick, lastCx = Integer.MIN_VALUE, lastCz, lastRadius;
    private long lastSweep;
    private boolean dirty;

    public LightDetector() {
        super("Light Detector", "Highlights lit cells below Y=0 where block light beats sky light.", Category.RENDER);
        denoiseMin.showIf(denoise::on);
        color.showIf(() -> colorMode.value == ColorMode.CUSTOM);
        add(radius, minLight, colorMode, color, opacity, fill, denoise, denoiseMin, maxCells, speed);
    }

    private void clear() {
        results.clear();
        queue.clear();
        order = new ArrayList<>();
        lastCx = Integer.MIN_VALUE;
        dirty = false;
    }

    @Override public void onEnable() { clear(); }

    @Override public void onDisable() { clear(); }

    @Override public void onWorldChanged() { clear(); }

    @Override
    public void onTick() {
        ClientWorld w = mc.world;
        if (w == null || mc.player == null) return;
        tick++;

        ChunkPos pc = mc.player.getChunkPos();
        int r = radius.i();
        boolean moved = pc.x != lastCx || pc.z != lastCz || r != lastRadius;
        if (moved || (queue.isEmpty() && tick - lastSweep >= 200)) {
            rebuildQueue(pc.x, pc.z, r);
            lastCx = pc.x;
            lastCz = pc.z;
            lastRadius = r;
            lastSweep = tick;
        }

        for (int i = 0; i < speed.i() && !queue.isEmpty(); i++) {
            long k = queue.poll();
            scan(w, ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), k);
        }

        if (dirty || tick % 20 == 0) {
            rebuildOrder(pc.x, pc.z);
            dirty = false;
        }
    }

    private void rebuildQueue(int pcx, int pcz, int r) {
        queue.clear();
        results.keySet().removeIf(k -> {
            int dx = ChunkPos.getPackedX(k) - pcx, dz = ChunkPos.getPackedZ(k) - pcz;
            return dx * dx + dz * dz > (r + 1) * (r + 1);
        });
        List<long[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r) continue;
                list.add(new long[]{d2, ChunkPos.toLong(pcx + dx, pcz + dz)});
            }
        }
        list.sort(Comparator.comparingLong(a -> a[0]));
        for (long[] e : list) queue.add(e[1]);
        dirty = true;
    }

    private void rebuildOrder(int pcx, int pcz) {
        List<Data> l = new ArrayList<>(results.values());
        l.sort(Comparator.comparingInt(d -> (d.cx - pcx) * (d.cx - pcx) + (d.cz - pcz) * (d.cz - pcz)));
        order = l;
    }

    private void scan(ClientWorld w, int cx, int cz, long key) {
        WorldChunk ch = w.getChunkManager().getWorldChunk(cx, cz);
        if (ch == null) {
            if (results.remove(key) != null) dirty = true;
            return;
        }
        int min = minLight.i();
        boolean dn = denoise.on();
        int need = denoiseMin.i();
        int bottom = w.getBottomY();
        int top = Math.min(-1, w.getTopYInclusive());
        Data d = new Data(cx, cz);
        BlockPos.Mutable p = new BlockPos.Mutable();
        BlockPos.Mutable q = new BlockPos.Mutable();
        int bx = cx << 4, bz = cz << 4;

        for (int y = bottom; y <= top; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    p.set(bx + x, y, bz + z);
                    int bl = w.getLightLevel(LightType.BLOCK, p);
                    if (bl < min) continue;
                    if (bl <= w.getLightLevel(LightType.SKY, p)) continue;
                    if (dn) {
                        int n = 0;
                        for (Direction dir : Direction.values()) {
                            q.set(p.getX() + dir.getOffsetX(), p.getY() + dir.getOffsetY(), p.getZ() + dir.getOffsetZ());
                            if (w.getLightLevel(LightType.BLOCK, q) >= min && ++n >= need) break;
                        }
                        if (n < need) continue;
                    }
                    d.pos.add(p.asLong());
                    d.lvl.add((byte) bl);
                }
            }
        }

        if (d.pos.isEmpty()) results.remove(key);
        else results.put(key, d);
        dirty = true;
    }

    @Override
    public void renderWorld(WorldRenderContext ctx) {
        if (order.isEmpty()) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ms.peek().getPositionMatrix();
        float a = opacity.f() / 100f;
        int cap = maxCells.i();
        boolean byLight = colorMode.value == ColorMode.LIGHT;
        int custom = color.rgb();

        RenderUtil.begin();
        if (fill.on()) {
            BufferBuilder q = RenderUtil.quads();
            int n = 0;
            outer:
            for (Data d : order) {
                for (int i = 0; i < d.pos.size(); i++) {
                    if (n++ >= cap) break outer;
                    BlockPos bp = BlockPos.fromLong(d.pos.getLong(i));
                    int rgb = byLight ? ColorUtil.lightColor(d.lvl.getByte(i)) : custom;
                    float x = (float) (bp.getX() - cam.x), y = (float) (bp.getY() - cam.y), z = (float) (bp.getZ() - cam.z);
                    RenderUtil.boxFill(q, m, x + 0.05f, y + 0.05f, z + 0.05f, x + 0.95f, y + 0.95f, z + 0.95f,
                            ColorUtil.argb(rgb, a * 0.35f));
                }
            }
            RenderUtil.draw(q);
        }

        BufferBuilder l = RenderUtil.lines(1.5f);
        int n = 0;
        outer2:
        for (Data d : order) {
            for (int i = 0; i < d.pos.size(); i++) {
                if (n++ >= cap) break outer2;
                BlockPos bp = BlockPos.fromLong(d.pos.getLong(i));
                int rgb = byLight ? ColorUtil.lightColor(d.lvl.getByte(i)) : custom;
                float x = (float) (bp.getX() - cam.x), y = (float) (bp.getY() - cam.y), z = (float) (bp.getZ() - cam.z);
                RenderUtil.boxLines(l, m, x, y, z, x + 1f, y + 1f, z + 1f, ColorUtil.argb(rgb, Math.min(1f, a + 0.25f)));
            }
        }
        RenderUtil.draw(l);
        RenderUtil.end();
    }
}
