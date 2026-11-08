package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.Clues;
import dev.nitro.util.Clues.ClueType;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.Frosted;
import dev.nitro.util.RenderUtil;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.*;
import java.util.function.Predicate;

/**
 * Base Finder with the Sus Chunk Finder merged in.
 *
 * Modes: Classic, ANBS+ Scan, Activity Clues, plus Amethyst-style Bypass / Light / ANBS+ Scan methods.
 * Everything runs on already-loaded client chunks, a few per tick, nearest first.
 */
public final class BaseFinder extends Module {
    public enum Mode {
        CLASSIC("Classic"),
        ANBS_PLUS("ANBS+ Scan"),
        ACTIVITY("Activity Clues"),
        BYPASS("Amethyst: Bypass"),
        LIGHT("Amethyst: Light"),
        AMETHYST_ANBS("Amethyst: ANBS+ Scan");
        private final String label;
        Mode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum OutlineMode {
        CATEGORY("By clue type"), SCORE("By score"), CUSTOM("Custom colour");
        private final String label;
        OutlineMode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum CardPos {
        TOP_RIGHT("Top right"), TOP_LEFT("Top left"), BOTTOM_RIGHT("Bottom right"), BOTTOM_LEFT("Bottom left");
        private final String label;
        CardPos(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    // ---- detection
    private final Setting.Choice<Mode> mode = new Setting.Choice<>("Mode", Mode.CLASSIC, Mode.class);
    private final Setting.Num sensitivity = new Setting.Num("Sensitivity", 50, 1, 100, 1);
    private final Setting.Num range = new Setting.Num("Range (chunks)", 10, 1, 32, 1);
    private final Setting.Num minCells = new Setting.Num("Min cells", 2, 1, 16, 1);
    private final Setting.Num cellThreshold = new Setting.Num("Cell threshold", 4, 1, 20, 0.5);
    private final Setting.Num lightSamples = new Setting.Num("Light samples / cell", 2, 1, 8, 1);
    private final Setting.Num lightMin = new Setting.Num("Min light", 8, 1, 15, 1);
    private final Setting.Num rescan = new Setting.Num("Rescan every (s)", 20, 2, 120, 1);
    private final Setting.Num speed = new Setting.Num("Scan speed", 1, 1, 4, 1);

    // ---- filters
    private final Setting.Bool fContainer = new Setting.Bool("Containers", true);
    private final Setting.Bool fMachinery = new Setting.Bool("Machinery", true);
    private final Setting.Bool fRedstone = new Setting.Bool("Redstone", true);
    private final Setting.Bool fGeode = new Setting.Bool("Geodes", true);
    private final Setting.Bool fGrowth = new Setting.Bool("Growth", true);
    private final Setting.Num minY = new Setting.Num("Min Y", -64, -64, 320, 1);
    private final Setting.Num maxY = new Setting.Num("Max Y", 320, -64, 320, 1);
    private final Setting.Num minClues = new Setting.Num("Min clue blocks", 3, 1, 50, 1);

    // ---- outlines / tracers
    private final Setting.Bool outline = new Setting.Bool("Outlines", true);
    private final Setting.Choice<OutlineMode> outlineMode = new Setting.Choice<>("Outline colour", OutlineMode.CATEGORY, OutlineMode.class);
    private final Setting.Color outlineColor = new Setting.Color("Custom outline", 0xFFB000);
    private final Setting.Num outlineOpacity = new Setting.Num("Outline opacity", 80, 10, 100, 5);
    private final Setting.Num fillOpacity = new Setting.Num("Fill opacity", 8, 0, 50, 1);
    private final Setting.Bool fullHeight = new Setting.Bool("Full height boxes", false);
    private final Setting.Num lineWidth = new Setting.Num("Line width", 2, 1, 5, 0.5);
    private final Setting.Bool tracers = new Setting.Bool("Tracers", false);
    private final Setting.Num maxTracers = new Setting.Num("Max tracers", 3, 1, 20, 1);

    // ---- cards
    private final Setting.Bool cards = new Setting.Bool("Discovery cards", true);
    private final Setting.Num maxCards = new Setting.Num("Max cards", 4, 1, 8, 1);
    private final Setting.Choice<CardPos> cardPos = new Setting.Choice<>("Card position", CardPos.TOP_RIGHT, CardPos.class);
    private final Setting.Color cardColor = new Setting.Color("Card tint", 0x20242C);
    private final Setting.Num cardOpacity = new Setting.Num("Card opacity", 60, 20, 95, 5);
    private final Setting.Bool cardIcons = new Setting.Bool("Item icons", true);
    private final Setting.Bool cardDetails = new Setting.Bool("Clue details", true);
    private final Setting.Color[] catColor = {
            new Setting.Color("Containers colour", 0xE8A33D),
            new Setting.Color("Machinery colour", 0x6EA8FF),
            new Setting.Color("Redstone colour", 0xFF4D4D),
            new Setting.Color("Geodes colour", 0xB77CFF),
            new Setting.Color("Growth colour", 0x5BD66B)
    };

    private static final class Result {
        final int cx, cz;
        float score, activity;
        final int[] cat = new int[5];
        final Object2IntOpenHashMap<Block> blocks = new Object2IntOpenHashMap<>();
        int clueCount, artificial, lit, cells;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int ay = Integer.MIN_VALUE;
        boolean flagged;
        Result(int cx, int cz) { this.cx = cx; this.cz = cz; }
        void span(int lo, int hi) {
            if (lo < minY) minY = lo;
            if (hi > maxY) maxY = hi;
        }
    }

    private final Map<Long, Result> all = new HashMap<>();
    private final Map<Long, float[]> base = new HashMap<>(); // activity baseline: {signature, decayed activity}
    private final Map<Long, Integer> lastScan = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private List<Result> display = new ArrayList<>();
    private int tick, lastCx = Integer.MIN_VALUE, lastCz, lastRange, lastSweep;
    private Mode lastMode;
    private boolean dirty;

    public BaseFinder() {
        super("Base Finder", "Sus chunk finder: Classic, ANBS+, Activity and Amethyst-style scan methods.", Module.Category.WORLD);
        minCells.showIf(() -> isCellMode(mode.value) || mode.value == Mode.LIGHT);
        cellThreshold.showIf(() -> isCellMode(mode.value));
        lightSamples.showIf(() -> mode.value == Mode.LIGHT);
        lightMin.showIf(() -> mode.value == Mode.LIGHT || mode.value == Mode.ACTIVITY);
        minClues.showIf(() -> mode.value != Mode.LIGHT && mode.value != Mode.BYPASS && mode.value != Mode.AMETHYST_ANBS && mode.value != Mode.ACTIVITY);
        outlineColor.showIf(() -> outlineMode.value == OutlineMode.CUSTOM);
        maxTracers.showIf(tracers::on);
        add(mode, sensitivity, range, minCells, cellThreshold, lightSamples, lightMin, rescan, speed);
        add(fContainer, fMachinery, fRedstone, fGeode, fGrowth, minY, maxY, minClues);
        add(outline, outlineMode, outlineColor, outlineOpacity, fillOpacity, fullHeight, lineWidth, tracers, maxTracers);
        add(cards, maxCards, cardPos, cardColor, cardOpacity, cardIcons, cardDetails);
        add(catColor);
    }


    private static boolean isCellMode(Mode m) {
        return m == Mode.ANBS_PLUS || m == Mode.AMETHYST_ANBS;
    }

    private void clear() {
        all.clear();
        base.clear();
        lastScan.clear();
        queue.clear();
        display = new ArrayList<>();
        lastCx = Integer.MIN_VALUE;
        dirty = false;
    }

    @Override public void onEnable() { clear(); lastMode = mode.value; }

    @Override public void onDisable() { clear(); }

    @Override public void onWorldChanged() { clear(); }

    private float sf() {
        return (150f - sensitivity.f()) / 100f;
    }

    @Override
    public void onTick() {
        ClientWorld w = mc.world;
        if (w == null || mc.player == null) return;
        tick++;

        if (lastMode != mode.value) {
            clear();
            lastMode = mode.value;
        }

        ChunkPos pc = mc.player.getChunkPos();
        int r = range.i();
        boolean moved = pc.x != lastCx || pc.z != lastCz || r != lastRange;
        if (moved || (queue.isEmpty() && tick - lastSweep >= rescan.i() * 20)) {
            rebuildQueue(pc.x, pc.z, r);
            lastCx = pc.x;
            lastCz = pc.z;
            lastRange = r;
            lastSweep = tick;
        }

        for (int i = 0; i < speed.i() && !queue.isEmpty(); i++) {
            long k = queue.poll();
            WorldChunk ch = w.getChunkManager().getWorldChunk(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k));
            if (ch == null) continue;
            handle(w, ch, k);
        }

        if (dirty || tick % 10 == 0) {
            rebuildDisplay(pc.x, pc.z, r);
            dirty = false;
        }
    }

    private void rebuildQueue(int pcx, int pcz, int r) {
        queue.clear();
        int far = (r + 2) * (r + 2);
        all.keySet().removeIf(k -> d2(k, pcx, pcz) > far);
        base.keySet().removeIf(k -> d2(k, pcx, pcz) > far);
        lastScan.keySet().removeIf(k -> d2(k, pcx, pcz) > far);

        int interval = rescan.i() * 20;
        List<long[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r) continue;
                long k = ChunkPos.toLong(pcx + dx, pcz + dz);
                Integer last = lastScan.get(k);
                if (last != null && tick - last < interval) continue;
                list.add(new long[]{d2, k});
            }
        }
        list.sort(Comparator.comparingLong(a -> a[0]));
        for (long[] e : list) queue.add(e[1]);
        dirty = true;
    }

    private static int d2(long k, int pcx, int pcz) {
        int dx = ChunkPos.getPackedX(k) - pcx, dz = ChunkPos.getPackedZ(k) - pcz;
        return dx * dx + dz * dz;
    }

    private void rebuildDisplay(int pcx, int pcz, int r) {
        List<Result> l = new ArrayList<>();
        for (Result x : all.values()) {
            if (x.flagged && (x.cx - pcx) * (x.cx - pcx) + (x.cz - pcz) * (x.cz - pcz) <= (r + 1) * (r + 1)) l.add(x);
        }
        l.sort((a, b) -> Float.compare(b.score, a.score));
        display = l;
    }

    // ------------------------------------------------------------------ scanning

    private void handle(ClientWorld w, WorldChunk ch, long key) {
        Mode m = mode.value;
        Result r = scan(w, ch, m);
        lastScan.put(key, tick);

        if (m == Mode.ACTIVITY) {
            float sig = r.score + r.lit * 0.5f;
            float[] b = base.get(key);
            float act = b == null ? 0f : b[1] * 0.9f + Math.abs(sig - b[0]);
            base.put(key, new float[]{sig, act});
            r.activity = act;
            r.score = act;
            r.flagged = b != null && act >= 6f * sf();
        }

        if (r.flagged) {
            if (r.ay == Integer.MIN_VALUE) r.ay = r.minY <= r.maxY ? (r.minY + r.maxY) / 2 : w.getBottomY() + w.getHeight() / 2;
            all.put(key, r);
        } else {
            all.remove(key);
        }
        dirty = true;
    }

    private Result scan(ClientWorld w, WorldChunk ch, Mode m) {
        Result r = new Result(ch.getPos().x, ch.getPos().z);
        boolean[] en = {fContainer.on(), fMachinery.on(), fRedstone.on(), fGeode.on(), fGrowth.on()};
        int bottom = ch.getBottomY();
        int fMin = minY.i(), fMax = maxY.i();
        boolean cellMode = isCellMode(m);
        boolean artOnly = m == Mode.AMETHYST_ANBS;
        float sf = sf();

        if (m == Mode.LIGHT) {
            float[] cells = lightCells(w, ch, r, bottom, fMin, fMax);
            int flagged = 0;
            int need = lightSamples.i();
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] >= need) {
                    flagged++;
                    r.score += cells[i];
                    int cy = bottom + (i >> 4) * 4;
                    r.span(cy, cy + 4);
                }
            }
            r.cells = flagged;
            r.flagged = flagged >= Math.max(1, Math.round(minCells.f() * sf));
            return r;
        }

        float[] cells = cellMode ? new float[16 * (ch.getHeight() >> 2)] : null;
        Predicate<BlockState> gate;
        if (cellMode) gate = st -> Clues.isClue(st.getBlock()) || Clues.isArtificial(st.getBlock());
        else gate = st -> Clues.isClue(st.getBlock());

        ChunkSection[] secs = ch.getSectionArray();
        int bx = ch.getPos().getStartX(), bz = ch.getPos().getStartZ();

        for (int si = 0; si < secs.length; si++) {
            ChunkSection sec = secs[si];
            if (sec == null || sec.isEmpty()) continue;
            int y0 = bottom + si * 16;
            if (y0 + 15 < fMin || y0 > fMax) continue;

            if (m == Mode.BYPASS) {
                // palette-only: ask each section whether it contains any block of a category, no per-block walk
                for (ClueType c : ClueType.values()) {
                    if (!en[c.ordinal()]) continue;
                    if (sec.hasAny(st -> Clues.category(st.getBlock()) == c)) {
                        r.cat[c.ordinal()]++;
                        r.clueCount++;
                        r.score += 1f;
                        r.span(y0, y0 + 15);
                    }
                }
                continue;
            }

            if (!sec.hasAny(gate)) continue;

            for (int yy = 0; yy < 16; yy++) {
                int y = y0 + yy;
                if (y < fMin || y > fMax) continue;
                for (int zz = 0; zz < 16; zz++) {
                    for (int xx = 0; xx < 16; xx++) {
                        BlockState st = sec.getBlockState(xx, yy, zz);
                        if (st.isAir()) continue;
                        Block b = st.getBlock();
                        Clues.Clue c = Clues.get(b);
                        boolean clueOn = c != null && en[c.cat().ordinal()];
                        float wgt = 0f;
                        if (clueOn) {
                            wgt = artOnly ? 1f : c.weight();
                            r.cat[c.cat().ordinal()]++;
                            r.blocks.addTo(b, 1);
                            r.clueCount++;
                            r.span(y, y);
                        } else if (cellMode && Clues.isArtificial(b)) {
                            wgt = artOnly ? 1f : 0.25f;
                            r.artificial++;
                        }
                        if (wgt > 0f) {
                            if (cellMode) cells[((y - bottom) >> 2) * 16 + (zz >> 2) * 4 + (xx >> 2)] += wgt;
                            else r.score += wgt;
                        }
                    }
                }
            }
        }

        if (m == Mode.ACTIVITY) {
            lightCells(w, ch, r, bottom, fMin, fMax); // fills r.lit
            return r; // flagged in handle()
        }

        if (cellMode) {
            float thr = cellThreshold.f() * sf;
            int flagged = 0;
            r.score = 0f;
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] >= thr) {
                    flagged++;
                    r.score += cells[i];
                    int cy = bottom + (i >> 4) * 4;
                    r.span(cy, cy + 3);
                }
            }
            r.cells = flagged;
            r.flagged = flagged >= Math.max(1, Math.round(minCells.f() * sf)) && (artOnly || r.clueCount >= minClues.i());
        } else if (m == Mode.BYPASS) {
            r.flagged = r.score >= Math.max(1, Math.round(3f * sf));
        } else { // CLASSIC
            r.flagged = r.score >= 12f * sf && r.clueCount >= minClues.i();
        }
        return r;
    }

    /** Samples every second block below the surface; returns per 4x4x4 cell counts of lit-above-sky samples. */
    private float[] lightCells(ClientWorld w, WorldChunk ch, Result r, int bottom, int fMin, int fMax) {
        float[] cells = new float[16 * (ch.getHeight() >> 2)];
        Heightmap hm = ch.getHeightmap(Heightmap.Type.WORLD_SURFACE);
        BlockPos.Mutable p = new BlockPos.Mutable();
        int bx = ch.getPos().getStartX(), bz = ch.getPos().getStartZ();
        int lo = Math.max(bottom, fMin);
        int min = lightMin.i();
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                int top = Math.min(hm.get(x, z) - 6, fMax);
                for (int y = lo; y <= top; y += 2) {
                    p.set(bx + x, y, bz + z);
                    int bl = w.getLightLevel(LightType.BLOCK, p);
                    if (bl < min) continue;
                    if (bl <= w.getLightLevel(LightType.SKY, p)) continue;
                    cells[((y - bottom) >> 2) * 16 + (z >> 2) * 4 + (x >> 2)] += 1f;
                    r.lit++;
                }
            }
        }
        return cells;
    }

    // ------------------------------------------------------------------ colours

    private int catRgb(ClueType c) {
        return catColor[c.ordinal()].rgb();
    }

    private int dominant(Result r) {
        int best = 0;
        for (int i = 1; i < 5; i++) if (r.cat[i] > r.cat[best]) best = i;
        return best;
    }

    private int resultRgb(Result r, float maxScore) {
        switch (outlineMode.value) {
            case CUSTOM:
                return outlineColor.rgb();
            case SCORE:
                return ColorUtil.lerp(0x55E08A, 0xFF4D4D, Math.min(1f, r.score / Math.max(1f, maxScore)));
            default:
                return catRgb(ClueType.values()[dominant(r)]);
        }
    }

    // ------------------------------------------------------------------ world render

    @Override
    public void renderWorld(WorldRenderContext ctx) {
        if (display.isEmpty() || (!outline.on() && !tracers.on())) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null || mc.world == null) return;
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ms.peek().getPositionMatrix();
        int wBottom = mc.world.getBottomY(), wTop = mc.world.getTopYInclusive() + 1;
        float maxScore = display.get(0).score;

        RenderUtil.begin();

        if (outline.on() && fillOpacity.i() > 0) {
            BufferBuilder q = RenderUtil.quads();
            for (Result r : display) {
                float[] b = box(r, cam, wBottom, wTop);
                RenderUtil.boxFill(q, m, b[0], b[1], b[2], b[3], b[4], b[5],
                        ColorUtil.argb(resultRgb(r, maxScore), fillOpacity.f() / 100f));
            }
            RenderUtil.draw(q);
        }

        BufferBuilder l = RenderUtil.lines(lineWidth.f());
        if (outline.on()) {
            for (Result r : display) {
                float[] b = box(r, cam, wBottom, wTop);
                RenderUtil.boxLines(l, m, b[0], b[1], b[2], b[3], b[4], b[5],
                        ColorUtil.argb(resultRgb(r, maxScore), outlineOpacity.f() / 100f));
            }
        }
        if (tracers.on()) {
            Vec3d look = Vec3d.fromPolar(ctx.camera().getPitch(), ctx.camera().getYaw());
            int n = Math.min(maxTracers.i(), display.size());
            for (int i = 0; i < n; i++) {
                Result r = display.get(i);
                RenderUtil.line(l, m, (float) look.x, (float) look.y, (float) look.z,
                        (float) (r.cx * 16 + 8 - cam.x), (float) (r.ay - cam.y), (float) (r.cz * 16 + 8 - cam.z),
                        ColorUtil.argb(resultRgb(r, maxScore), 0.9f));
            }
        }
        RenderUtil.draw(l);
        RenderUtil.end();
    }

    private float[] box(Result r, Vec3d cam, int wBottom, int wTop) {
        int lo, hi;
        if (fullHeight.on() || r.minY > r.maxY) {
            lo = wBottom;
            hi = wTop;
        } else {
            lo = r.minY;
            hi = r.maxY + 1;
        }
        float x1 = (float) (r.cx * 16 - cam.x), z1 = (float) (r.cz * 16 - cam.z);
        return new float[]{x1, (float) (lo - cam.y), z1, x1 + 16f, (float) (hi - cam.y), z1 + 16f};
    }

    // ------------------------------------------------------------------ HUD cards

    @Override
    public void renderHud(DrawContext ctx) {
        if (!cards.on() || display.isEmpty() || mc.player == null || mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int n = Math.min(maxCards.i(), display.size());
        final int W = 156, gap = 5, margin = 8;
        float maxScore = Math.max(1f, display.get(0).score);

        int[] heights = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            heights[i] = cardHeight(display.get(i));
            total += heights[i] + (i > 0 ? gap : 0);
        }

        CardPos pos = cardPos.value;
        boolean right = pos == CardPos.TOP_RIGHT || pos == CardPos.BOTTOM_RIGHT;
        boolean top = pos == CardPos.TOP_RIGHT || pos == CardPos.TOP_LEFT;
        int x = right ? sw - W - margin : margin;
        int y = top ? margin : sh - total - margin;

        for (int i = 0; i < n; i++) {
            drawCard(ctx, tr, display.get(i), x, y, W, heights[i], maxScore);
            y += heights[i] + gap;
        }
    }

    private List<Object[]> detailLines(Result r) {
        List<Object[]> lines = new ArrayList<>();
        Integer[] idx = {0, 1, 2, 3, 4};
        Arrays.sort(idx, (a, b) -> r.cat[b] - r.cat[a]);
        for (int i : idx) {
            if (r.cat[i] <= 0 || lines.size() >= 3) continue;
            ClueType c = ClueType.values()[i];
            lines.add(new Object[]{c.label + " " + r.cat[i], catRgb(c)});
        }
        if (r.cells > 0) lines.add(new Object[]{"Cells " + r.cells, 0xB8B8C0});
        if (r.artificial > 0 && lines.size() < 4) lines.add(new Object[]{"Man-made blocks " + r.artificial, 0xB8B8C0});
        if (r.activity > 0) lines.add(new Object[]{"Activity +" + String.format("%.1f", r.activity), 0xFFD27A});
        if (r.lit > 0 && lines.size() < 4) lines.add(new Object[]{"Lit samples " + r.lit, 0xFFD27A});
        return lines;
    }

    private int cardHeight(Result r) {
        int h = 5 + 12 + 7;
        if (cardIcons.on()) h += 17;
        if (cardDetails.on()) h += detailLines(r).size() * 10;
        return h + 5;
    }

    private void drawCard(DrawContext ctx, TextRenderer tr, Result r, int x, int y, int W, int h, float maxScore) {
        int accent = catRgb(ClueType.values()[dominant(r)]);
        Frosted.draw(ctx, x, y, W, h, cardColor.rgb(), cardOpacity.f() / 100f, accent);

        int ix = x + 8, cy = y + 5;
        ctx.drawText(tr, "Chunk " + r.cx + ", " + r.cz, ix, cy, 0xFFFFFFFF, false);
        double dx = mc.player.getX() - (r.cx * 16 + 8), dz = mc.player.getZ() - (r.cz * 16 + 8);
        String dist = (int) Math.sqrt(dx * dx + dz * dz) + "m";
        ctx.drawText(tr, dist, x + W - 6 - tr.getWidth(dist), cy, 0xFFB8B8C0, false);
        cy += 12;

        int bw = W - 16;
        ctx.fill(ix, cy, ix + bw, cy + 3, 0x44000000);
        ctx.fill(ix, cy, ix + Math.max(2, (int) (bw * Math.min(1f, r.score / maxScore))), cy + 3, ColorUtil.argb(accent, 1f));
        cy += 7;

        if (cardIcons.on()) {
            List<Object[]> icons = new ArrayList<>();
            List<Object2IntMap.Entry<Block>> es = new ArrayList<>(r.blocks.object2IntEntrySet());
            es.sort((a, b) -> b.getIntValue() - a.getIntValue());
            for (Object2IntMap.Entry<Block> e : es) {
                if (icons.size() >= 4) break;
                Clues.Clue c = Clues.get(e.getKey());
                if (c != null) icons.add(new Object[]{c.icon(), e.getIntValue()});
            }
            if (icons.isEmpty()) {
                for (ClueType c : ClueType.values()) {
                    if (r.cat[c.ordinal()] > 0 && icons.size() < 4) icons.add(new Object[]{c.icon, r.cat[c.ordinal()]});
                }
            }
            int ex = 0;
            for (Object[] ic : icons) {
                ctx.getMatrices().push();
                ctx.getMatrices().translate(ix + ex, cy, 0f);
                ctx.getMatrices().scale(0.75f, 0.75f, 1f);
                ctx.drawItem(new ItemStack((Item) ic[0]), 0, 0);
                ctx.getMatrices().pop();
                ctx.drawText(tr, "x" + ic[1], ix + ex + 13, cy + 3, 0xFFFFFFFF, false);
                ex += 34;
            }
            cy += 17;
        }

        if (cardDetails.on()) {
            for (Object[] line : detailLines(r)) {
                ctx.drawText(tr, (String) line[0], ix, cy, 0xFF000000 | (int) line[1], false);
                cy += 10;
            }
        }
    }
}
