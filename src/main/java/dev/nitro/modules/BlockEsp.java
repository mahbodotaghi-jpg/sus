package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.RenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Block ESP: outlines any blocks you pick through walls. The "Blocks" setting lists every block with its icon
 * (search, page, click to select). Blocks without an item (lava, water, fire ...) can be added by id in "Extra block ids".
 * Only loaded client chunks are scanned, a few per tick nearest-first, using palette checks so empty sections are free.
 */
public final class BlockEsp extends Module {
    private static final int PER_TICK = 3;       // chunks scanned per tick
    private static final int STALE_TICKS = 100;  // re-scan a chunk after this long
    private static final int PER_CHUNK_CAP = 4000;

    private final Setting.BlockSet blocks = new Setting.BlockSet("Blocks (click to choose)");
    private final Setting.Str extra = new Setting.Str("Extra block ids", "", "e.g. lava, minecraft:fire", 160);
    private final Setting.Num range = new Setting.Num("Range (chunks)", 4, 1, 16, 1);
    private final Setting.Num maxBlocks = new Setting.Num("Max blocks drawn", 1500, 100, 5000, 100);
    private final Setting.Bool autoColor = new Setting.Bool("Colour by block", true);
    private final Setting.Color color = new Setting.Color("Colour", 0x4DE8FF);
    private final Setting.Bool fill = new Setting.Bool("Fill", true);
    private final Setting.Num fillOpacity = new Setting.Num("Fill opacity", 20, 0, 50, 1);
    private final Setting.Num outlineOpacity = new Setting.Num("Outline opacity", 90, 10, 100, 5);
    private final Setting.Num lineWidth = new Setting.Num("Line width", 2, 1, 5, 0.5);
    private final Setting.Bool tracers = new Setting.Bool("Tracers", false);

    private record Hit(BlockPos pos, int rgb) {}

    private record Entry(List<Hit> hits, int scannedAt) {}

    private final Map<Long, Entry> cache = new HashMap<>();
    private List<Hit> shown = new ArrayList<>();
    private int tick, sig;

    private static Map<String, Block> names;
    private String parsedFor = "";
    private List<Block> parsed = List.of();

    public BlockEsp() {
        super("Block ESP", "Highlights any blocks you pick (browse every block with its icon in the settings).", Category.RENDER);
        add(blocks, extra, range, maxBlocks, autoColor, color, fill, fillOpacity, outlineOpacity, lineWidth, tracers);
        color.showIf(() -> !autoColor.on());
        fillOpacity.showIf(fill::on);
    }

    @Override
    public void onEnable() {
        clear();
    }

    @Override
    public void onDisable() {
        clear();
    }

    @Override
    public void onWorldChanged() {
        clear();
    }

    private void clear() {
        cache.clear();
        shown = new ArrayList<>();
        sig = 0;
        tick = 0;
    }

    // ------------------------------------------------------------ targets

    private static Map<String, Block> names() {
        if (names == null) {
            Map<String, Block> m = new HashMap<>();
            for (Block b : Registries.BLOCK) {
                if (b == Blocks.AIR) continue;
                m.put(Registries.BLOCK.getId(b).toString(), b);
            }
            names = m;
        }
        return names;
    }

    private List<Block> extraBlocks() {
        String v = extra.value;
        if (!v.equals(parsedFor)) {
            parsedFor = v;
            List<Block> out = new ArrayList<>();
            for (String tok : v.toLowerCase(Locale.ROOT).split("[,;\\s]+")) {
                if (tok.isEmpty()) continue;
                Block b = names().get(tok.contains(":") ? tok : "minecraft:" + tok);
                if (b != null) out.add(b);
            }
            parsed = out;
        }
        return parsed;
    }

    private int colorOf(Block b) {
        if (!autoColor.on()) return color.rgb();
        int h = Registries.BLOCK.getId(b).toString().hashCode();
        return ColorUtil.hsv((h & 0xFFFF) / 65535f, 0.65f, 1f);
    }

    private Map<Block, Integer> targets() {
        Map<Block, Integer> m = new HashMap<>();
        for (Block b : blocks.value) m.put(b, colorOf(b));
        for (Block b : extraBlocks()) m.put(b, colorOf(b));
        return m;
    }

    // ------------------------------------------------------------ scanning

    private List<Hit> scan(WorldChunk ch, Map<Block, Integer> targets) {
        List<Hit> out = new ArrayList<>();
        ChunkSection[] secs = ch.getSectionArray();
        int bottom = ch.getBottomY();
        int bx = ch.getPos().getStartX(), bz = ch.getPos().getStartZ();
        outer:
        for (int si = 0; si < secs.length; si++) {
            ChunkSection sec = secs[si];
            if (sec == null || sec.isEmpty()) continue;
            if (!sec.hasAny(st -> targets.containsKey(st.getBlock()))) continue;
            int y0 = bottom + si * 16;
            for (int yy = 0; yy < 16; yy++) {
                for (int zz = 0; zz < 16; zz++) {
                    for (int xx = 0; xx < 16; xx++) {
                        BlockState st = sec.getBlockState(xx, yy, zz);
                        Integer c = targets.get(st.getBlock());
                        if (c == null) continue;
                        out.add(new Hit(new BlockPos(bx + xx, y0 + yy, bz + zz), c));
                        if (out.size() >= PER_CHUNK_CAP) break outer;
                    }
                }
            }
        }
        return out;
    }

    private static long ckey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    @Override
    public void onTick() {
        tick++;
        ClientWorld w = mc.world;
        if (w == null) return;

        Map<Block, Integer> targets = targets();
        if (targets.isEmpty()) {
            cache.clear();
            shown = new ArrayList<>();
            return;
        }
        int s = targets.hashCode();
        if (s != sig) {
            sig = s;
            cache.clear();
        }

        Vec3d c = mc.gameRenderer.getCamera().getPos(); // follows Freecam
        int ccx = (int) Math.floor(c.x) >> 4, ccz = (int) Math.floor(c.z) >> 4;
        int r = range.i();

        if (tick % 4 == 0) {
            cache.keySet().removeIf(k -> {
                int cx = (int) (k >> 32), cz = (int) (long) k;
                return Math.abs(cx - ccx) > r + 1 || Math.abs(cz - ccz) > r + 1;
            });
            List<int[]> todo = new ArrayList<>();
            for (int cx = ccx - r; cx <= ccx + r; cx++) {
                for (int cz = ccz - r; cz <= ccz + r; cz++) {
                    Entry e = cache.get(ckey(cx, cz));
                    if (e != null && tick - e.scannedAt() < STALE_TICKS) continue;
                    todo.add(new int[]{cx, cz, (cx - ccx) * (cx - ccx) + (cz - ccz) * (cz - ccz)});
                }
            }
            todo.sort(Comparator.<int[]>comparingInt(a -> a[2]));
            int done = 0;
            for (int[] t : todo) {
                WorldChunk ch = w.getChunkManager().getWorldChunk(t[0], t[1]);
                if (ch == null) continue;
                cache.put(ckey(t[0], t[1]), new Entry(scan(ch, targets), tick));
                if (++done >= PER_TICK * 4) break; // scan runs every 4th tick
            }
        }

        if (tick % 5 == 0) rebuild(c);
    }

    private void rebuild(Vec3d cam) {
        List<Hit> all = new ArrayList<>();
        for (Entry e : cache.values()) all.addAll(e.hits());
        int cap = maxBlocks.i();
        if (all.size() > cap) {
            all.sort(Comparator.<Hit>comparingDouble(h -> {
                double dx = h.pos().getX() + 0.5 - cam.x, dy = h.pos().getY() + 0.5 - cam.y, dz = h.pos().getZ() + 0.5 - cam.z;
                return dx * dx + dy * dy + dz * dz;
            }));
            all = new ArrayList<>(all.subList(0, cap));
        }
        shown = all;
    }

    // ------------------------------------------------------------ render

    @Override
    public void renderWorld(WorldRenderContext ctx) {
        List<Hit> list = shown;
        if (list.isEmpty()) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        Camera camera = ctx.camera();
        Vec3d cam = camera.getPos();
        Matrix4f m = ms.peek().getPositionMatrix();
        float oa = outlineOpacity.f() / 100f, fa = fillOpacity.f() / 100f;

        RenderUtil.begin();
        if (fill.on()) {
            BufferBuilder q = RenderUtil.quads();
            for (Hit h : list) {
                BlockPos p = h.pos();
                float x1 = (float) (p.getX() - cam.x), y1 = (float) (p.getY() - cam.y), z1 = (float) (p.getZ() - cam.z);
                RenderUtil.boxFill(q, m, x1, y1, z1, x1 + 1f, y1 + 1f, z1 + 1f, ColorUtil.argb(h.rgb(), fa));
            }
            RenderUtil.draw(q);
        }

        BufferBuilder l = RenderUtil.lines(lineWidth.f());
        Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        float sx = (float) (look.x * 0.2), sy = (float) (look.y * 0.2), sz = (float) (look.z * 0.2);
        for (Hit h : list) {
            BlockPos p = h.pos();
            int c = ColorUtil.argb(h.rgb(), oa);
            float x1 = (float) (p.getX() - cam.x), y1 = (float) (p.getY() - cam.y), z1 = (float) (p.getZ() - cam.z);
            RenderUtil.boxLines(l, m, x1, y1, z1, x1 + 1f, y1 + 1f, z1 + 1f, c);
            if (tracers.on()) RenderUtil.line(l, m, sx, sy, sz, x1 + 0.5f, y1 + 0.5f, z1 + 0.5f, c);
        }
        RenderUtil.draw(l);
        RenderUtil.end();
    }
}
