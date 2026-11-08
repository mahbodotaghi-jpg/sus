package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.RenderUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Seed Ore ESP: re-runs Minecraft's overworld ore generation (1.21.4) from the world seed and outlines where the
 * ore veins were generated, even if the server hides them (anti-xray). Nothing is read from the server except the
 * terrain the client already has: the simulation needs it to know where stone is and where caves are.
 *
 * How it works: for every chunk, vanilla seeds a random from (seed, chunk, feature index, generation step), then for each
 * ore feature draws count -> x/z -> height -> vein shape. This class replays those exact draws and the vein algorithm
 * (OreFeature), including the "discard chance when touching air" rule, using the client's blocks as the terrain.
 *
 * Limits: overworld only; positions are wrong where the terrain was changed after generation (mined veins / dug caves
 * shift the random stream for the rest of that feature in that chunk); the feature indices below are the position of
 * each ore feature in the merged overworld feature list (use "Index offset" if the HUD shows low confirmation).
 */
public final class SeedOreEsp extends Module {
    private enum Ore {
        COAL("Coal", 0x9A9A9A, false, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
        IRON("Iron", 0xE0B89A, false, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
        GOLD("Gold", 0xFFD43B, true, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
        REDSTONE("Redstone", 0xFF3B3B, false, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
        LAPIS("Lapis", 0x3B6CFF, false, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
        COPPER("Copper", 0xE8794A, false, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
        DIAMOND("Diamond", 0x4DE8FF, true, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
        EMERALD("Emerald", 0x2EE66B, false, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);

        final String label;
        final int color;
        final boolean on;
        final Block[] blocks;

        Ore(String label, int color, boolean on, Block... blocks) {
            this.label = label;
            this.color = color;
            this.on = on;
            this.blocks = blocks;
        }
    }

    // ------------------------------------------------------------ java.util.Random compatible LCG (what worldgen uses)

    private static final class Rnd {
        private long seed;

        void setSeed(long s) {
            seed = (s ^ 0x5DEECE66DL) & ((1L << 48) - 1);
        }

        int next(int bits) {
            seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            return (int) (seed >>> (48 - bits));
        }

        int nextInt(int bound) {
            if ((bound & (bound - 1)) == 0) return (int) ((bound * (long) next(31)) >> 31);
            int bits, val;
            do {
                bits = next(31);
                val = bits % bound;
            } while (bits - val + (bound - 1) < 0);
            return val;
        }

        long nextLong() {
            int a = next(32);
            int b = next(32);
            return ((long) a << 32) + b;
        }

        float nextFloat() {
            return next(24) / (float) (1 << 24);
        }

        double nextDouble() {
            long a = next(26);
            long b = next(27);
            return ((a << 27) + b) * 1.1102230246251565E-16;
        }

        /** MathHelper.nextBetween: inclusive on both ends. */
        int between(int min, int max) {
            return nextInt(max - min + 1) + min;
        }
    }

    // ------------------------------------------------------------ feature table (UNDERGROUND_ORES, step 6)

    private interface Count {
        int get(Rnd r);
    }

    private interface Height {
        int get(Rnd r, int bottom);
    }

    private record Feature(Ore ore, int index, int size, float discard, Count count, Height height) {}

    private static final int STEP = 6;
    private static final List<Feature> FEATURES = new ArrayList<>();

    private static int uni(Rnd r, int lo, int hi) {
        return r.between(lo, hi);
    }

    /** TrapezoidHeightProvider with plateau 0 (a triangle). */
    private static int tri(Rnd r, int lo, int hi) {
        int k = hi - lo;
        int l = k / 2;
        int m = k - l;
        return lo + r.between(0, m) + r.between(0, l);
    }

    private static void add(Ore ore, int index, int size, float discard, Count count, Height height) {
        FEATURES.add(new Feature(ore, index, size, discard, count, height));
    }

    static {
        add(Ore.COAL, 9, 17, 0f, r -> 30, (r, b) -> uni(r, 136, 320));                  // ore_coal_upper
        add(Ore.COAL, 10, 17, 0.5f, r -> 20, (r, b) -> tri(r, b, 192));                 // ore_coal_lower
        add(Ore.IRON, 11, 9, 0f, r -> 90, (r, b) -> tri(r, 80, 384));                   // ore_iron_upper
        add(Ore.IRON, 12, 9, 0f, r -> 10, (r, b) -> tri(r, -24, 56));                   // ore_iron_middle
        add(Ore.IRON, 13, 4, 0f, r -> 10, (r, b) -> uni(r, b, 72));                     // ore_iron_small
        add(Ore.GOLD, 14, 9, 0.5f, r -> 4, (r, b) -> tri(r, -64, 32));                  // ore_gold
        add(Ore.GOLD, 15, 9, 0.5f, r -> r.nextInt(2), (r, b) -> uni(r, -64, -48));      // ore_gold_lower
        add(Ore.REDSTONE, 16, 8, 0f, r -> 4, (r, b) -> uni(r, b, 15));                  // ore_redstone
        add(Ore.REDSTONE, 17, 8, 0f, r -> 8, (r, b) -> tri(r, b - 32, b + 32));         // ore_redstone_lower
        add(Ore.DIAMOND, 18, 4, 0.5f, r -> 7, (r, b) -> tri(r, b - 80, b + 80));        // ore_diamond
        add(Ore.DIAMOND, 19, 8, 0.5f, r -> 2, (r, b) -> uni(r, -64, -4));               // ore_diamond_medium
        add(Ore.DIAMOND, 20, 12, 0.7f, r -> r.nextFloat() < 1.0f / 9.0f ? 1 : 0,        // ore_diamond_large (rarity 9)
                (r, b) -> tri(r, b - 80, b + 80));
        add(Ore.DIAMOND, 21, 8, 1.0f, r -> 4, (r, b) -> tri(r, b - 80, b + 80));        // ore_diamond_buried
        add(Ore.LAPIS, 22, 7, 0f, r -> 2, (r, b) -> tri(r, b - 32, b + 32));            // ore_lapis
        add(Ore.LAPIS, 23, 7, 1.0f, r -> 4, (r, b) -> uni(r, b, 64));                   // ore_lapis_buried
        add(Ore.COPPER, 24, 10, 0f, r -> 16, (r, b) -> tri(r, -16, 112));               // ore_copper
        add(Ore.EMERALD, 26, 3, 0f, r -> 100, (r, b) -> tri(r, -16, 480));              // ore_emerald (mountain biomes)
    }

    private static final Set<RegistryKey<Biome>> EMERALD_BIOMES = Set.of(
            BiomeKeys.WINDSWEPT_HILLS, BiomeKeys.WINDSWEPT_GRAVELLY_HILLS, BiomeKeys.WINDSWEPT_FOREST,
            BiomeKeys.MEADOW, BiomeKeys.CHERRY_GROVE, BiomeKeys.GROVE, BiomeKeys.SNOWY_SLOPES,
            BiomeKeys.JAGGED_PEAKS, BiomeKeys.FROZEN_PEAKS, BiomeKeys.STONY_PEAKS);

    private static final Set<Block> ALL_ORE_BLOCKS = new HashSet<>();
    private static final Set<Block> DEEPSLATE_ORE_BLOCKS = new HashSet<>();

    static {
        for (Ore o : Ore.values()) {
            Collections.addAll(ALL_ORE_BLOCKS, o.blocks);
            DEEPSLATE_ORE_BLOCKS.add(o.blocks[1]);
        }
    }

    // ------------------------------------------------------------ settings

    private final Setting.Str seedText = new Setting.Str("World seed", "6608149111735331168", "numeric seed", 24);
    private final Setting.Num range = new Setting.Num("Range (chunks)", 3, 1, 8, 1);
    private final Setting.Num maxBlocks = new Setting.Num("Max blocks drawn", 1500, 100, 5000, 100);
    private final Setting.Picks picks = makePicks();
    private final Setting.Color[] col = new Setting.Color[Ore.values().length];
    private final Setting.Num indexOffset = new Setting.Num("Index offset", 0, -10, 10, 1);
    private final Setting.Bool stats = new Setting.Bool("Show match stats", true);
    private final Setting.Bool fill = new Setting.Bool("Fill", true);
    private final Setting.Num fillOpacity = new Setting.Num("Fill opacity", 20, 0, 50, 1);
    private final Setting.Num outlineOpacity = new Setting.Num("Outline opacity", 90, 10, 100, 5);
    private final Setting.Num lineWidth = new Setting.Num("Line width", 2, 1, 5, 0.5);
    private final Setting.Bool tracers = new Setting.Bool("Tracers", false);

    // ------------------------------------------------------------ state

    /** variant 0 = stone ore, 1 = deepslate ore */
    private record Hit(int x, int y, int z, int ore, int variant) {}

    private record Entry(List<Hit> hits, int scannedAt, boolean complete, int exposed, int confirmed) {}

    private final Map<Long, Entry> cache = new HashMap<>();
    private List<Hit> shown = new ArrayList<>();
    private int tick, shownExposed, shownConfirmed;
    private String sig = "";
    private boolean overworld = true;

    public SeedOreEsp() {
        super("Seed Ore ESP", "Predicts ore veins from the world seed and outlines them through walls (overworld).", Category.RENDER);
        add(seedText, range, maxBlocks);
        add(picks);
        int n = Ore.values().length;
        for (Ore o : Ore.values()) {
            int i = o.ordinal();
            col[i] = new Setting.Color(o.label + " colour", o.color);
            col[i].showIf(() -> picks.on(i) || picks.on(n + i));
            add(col[i]);
        }
        add(indexOffset, stats, fill, fillOpacity, outlineOpacity, lineWidth, tracers);
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
        shownExposed = shownConfirmed = 0;
        sig = "";
        tick = 0;
    }

    private static Setting.Picks makePicks() {
        Ore[] ores = Ore.values();
        int n = ores.length;
        String[] labels = new String[n * 2];
        Block[] icons = new Block[n * 2];
        boolean[] def = new boolean[n * 2];
        for (Ore o : ores) {
            int i = o.ordinal();
            labels[i] = o.label + " ore";
            icons[i] = o.blocks[0];
            def[i] = o.on;
            labels[n + i] = "Deepslate " + o.label.toLowerCase(Locale.ROOT) + " ore";
            icons[n + i] = o.blocks[1];
            def[n + i] = o.on;
        }
        return new Setting.Picks("Ores (click to choose)", labels, icons, def, n);
    }

    private static long parseSeed(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode(); // same rule as the world creation screen
        }
    }

    // ------------------------------------------------------------ simulation

    /** The 3x3 chunks around the origin chunk plus everything the simulation produces for it. */
    private static final class Sim {
        final ClientWorld w;
        final int cx, cz, bottom, top;
        final WorldChunk[] chunks = new WorldChunk[9];
        final LongOpenHashSet claimed = new LongOpenHashSet();
        final List<Hit> hits = new ArrayList<>();
        final BlockPos.Mutable mp = new BlockPos.Mutable();
        int loaded;

        Sim(ClientWorld w, int cx, int cz) {
            this.w = w;
            this.cx = cx;
            this.cz = cz;
            this.bottom = w.getBottomY();
            this.top = w.getBottomY() + w.getHeight();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    WorldChunk ch = w.getChunkManager().getWorldChunk(cx + dx, cz + dz);
                    chunks[(dx + 1) * 3 + (dz + 1)] = ch;
                    if (ch != null) loaded++;
                }
            }
        }

        boolean inWindow(int x, int z) {
            return Math.abs((x >> 4) - cx) <= 1 && Math.abs((z >> 4) - cz) <= 1;
        }

        WorldChunk chunkAt(int x, int z) {
            int dx = (x >> 4) - cx + 1, dz = (z >> 4) - cz + 1;
            if (dx < 0 || dx > 2 || dz < 0 || dz > 2) return null;
            return chunks[dx * 3 + dz];
        }

        /** null = chunk not loaded (treated like plain stone). */
        BlockState stateAt(int x, int y, int z) {
            WorldChunk ch = chunkAt(x, z);
            if (ch == null) return null;
            return ch.getBlockState(mp.set(x, y, z));
        }

        /** 1 if the vein block at this spot is the deepslate version of its ore, else 0. */
        int variant(int x, int y, int z) {
            BlockState st = stateAt(x, y, z);
            if (st == null) return y < 0 ? 1 : 0;
            Block b = st.getBlock();
            if (DEEPSLATE_ORE_BLOCKS.contains(b)) return 1;
            if (ALL_ORE_BLOCKS.contains(b)) return 0;
            return st.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES) ? 1 : 0;
        }

        boolean placeable(int x, int y, int z) {
            if (claimed.contains(BlockPos.asLong(x, y, z))) return false;
            BlockState st = stateAt(x, y, z);
            if (st == null) return true;
            return st.isIn(BlockTags.BASE_STONE_OVERWORLD) || ALL_ORE_BLOCKS.contains(st.getBlock());
        }

        boolean adjacentToAir(int x, int y, int z) {
            return air(x + 1, y, z) || air(x - 1, y, z) || air(x, y + 1, z) || air(x, y - 1, z) || air(x, y, z + 1) || air(x, y, z - 1);
        }

        private boolean air(int x, int y, int z) {
            if (claimed.contains(BlockPos.asLong(x, y, z))) return false;
            BlockState st = stateAt(x, y, z);
            return st != null && st.isAir();
        }

        /** Approximation of the OCEAN_FLOOR_WG height from the client's surface heightmap. */
        int topY(int x, int z) {
            WorldChunk ch = chunkAt(x, z);
            if (ch == null) return Integer.MAX_VALUE;
            int v = ch.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(x & 15, z & 15);
            return v <= bottom ? Integer.MAX_VALUE : v;
        }
    }

    private Entry simulate(ClientWorld w, long seed, int cx, int cz, int tick) {
        Sim s = new Sim(w, cx, cz);
        Rnd r = new Rnd();
        r.setSeed(seed);
        long a = r.nextLong() | 1L;
        long b = r.nextLong() | 1L;
        int bx = cx * 16, bz = cz * 16;
        long pop = ((long) bx * a + (long) bz * b) ^ seed;
        int off = indexOffset.i();

        for (Feature f : FEATURES) {
            r.setSeed(pop + (long) (f.index() + off) + 10000L * STEP);
            int n = f.count().get(r);
            for (int i = 0; i < n; i++) {
                int x = r.nextInt(16) + bx;
                int z = r.nextInt(16) + bz;
                int y = f.height().get(r, s.bottom);
                if (f.ore() == Ore.EMERALD && !emeraldBiome(w, x, y, z)) continue;
                vein(r, f, x, y, z, s);
            }
        }

        int exposed = 0, confirmed = 0;
        for (Hit h : s.hits) {
            BlockState st = s.stateAt(h.x(), h.y(), h.z());
            if (st == null) continue;
            boolean exp = false;
            for (int k = 0; k < 6 && !exp; k++) {
                int nx = h.x() + (k == 0 ? 1 : k == 1 ? -1 : 0);
                int ny = h.y() + (k == 2 ? 1 : k == 3 ? -1 : 0);
                int nz = h.z() + (k == 4 ? 1 : k == 5 ? -1 : 0);
                BlockState ns = s.stateAt(nx, ny, nz);
                exp = ns != null && ns.isAir();
            }
            if (!exp) continue;
            exposed++;
            if (st.isOf(Ore.values()[h.ore()].blocks[h.variant()])) confirmed++;
        }
        return new Entry(s.hits, tick, s.loaded == 9, exposed, confirmed);
    }

    private static boolean emeraldBiome(ClientWorld w, int x, int y, int z) {
        return w.getBiome(new BlockPos(x, y, z)).getKey().map(EMERALD_BIOMES::contains).orElse(false);
    }

    /** net.minecraft.world.gen.feature.OreFeature#generate for one vein. */
    private void vein(Rnd r, Feature f, int ox, int oy, int oz, Sim s) {
        int size = f.size();
        float ang = r.nextFloat() * (float) Math.PI;
        float g = (float) size / 8.0F;
        int i = MathHelper.ceil(((float) size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double sx = (double) ox + Math.sin((double) ang) * (double) g;
        double ex = (double) ox - Math.sin((double) ang) * (double) g;
        double sz = (double) oz + Math.cos((double) ang) * (double) g;
        double ez = (double) oz - Math.cos((double) ang) * (double) g;
        double sy = (double) (oy + r.nextInt(3) - 2);
        double ey = (double) (oy + r.nextInt(3) - 2);
        int n = ox - MathHelper.ceil(g) - i;
        int o = oy - 2 - i;
        int p = oz - MathHelper.ceil(g) - i;
        int q = 2 * (MathHelper.ceil(g) + i);

        boolean reachable = false;
        outer:
        for (int x = n; x <= n + q; x++) {
            for (int z = p; z <= p + q; z++) {
                if (o <= s.topY(x, z)) {
                    reachable = true;
                    break outer;
                }
            }
        }
        if (!reachable) return;

        int j = size;
        double[] ds = new double[j * 4];
        for (int k = 0; k < j; k++) {
            float t = (float) k / (float) j;
            double dx = MathHelper.lerp((double) t, sx, ex);
            double dy = MathHelper.lerp((double) t, sy, ey);
            double dz = MathHelper.lerp((double) t, sz, ez);
            double h = r.nextDouble() * (double) j / 16.0;
            double rad = ((double) (MathHelper.sin((float) Math.PI * t) + 1.0F) * h + 1.0) / 2.0;
            ds[k * 4] = dx;
            ds[k * 4 + 1] = dy;
            ds[k * 4 + 2] = dz;
            ds[k * 4 + 3] = rad;
        }
        for (int k = 0; k < j - 1; k++) {
            if (ds[k * 4 + 3] <= 0.0) continue;
            for (int m = k + 1; m < j; m++) {
                if (ds[m * 4 + 3] <= 0.0) continue;
                double fx = ds[k * 4] - ds[m * 4];
                double fy = ds[k * 4 + 1] - ds[m * 4 + 1];
                double fz = ds[k * 4 + 2] - ds[m * 4 + 2];
                double dr = ds[k * 4 + 3] - ds[m * 4 + 3];
                if (dr * dr > fx * fx + fy * fy + fz * fz) {
                    if (dr > 0.0) ds[m * 4 + 3] = -1.0;
                    else ds[k * 4 + 3] = -1.0;
                }
            }
        }

        LongOpenHashSet seen = new LongOpenHashSet();
        float discard = f.discard();
        for (int k = 0; k < j; k++) {
            double d = ds[k * 4 + 3];
            if (d < 0.0) continue;
            double cx = ds[k * 4], cy = ds[k * 4 + 1], cz = ds[k * 4 + 2];
            int x0 = Math.max(MathHelper.floor(cx - d), n);
            int y0 = Math.max(MathHelper.floor(cy - d), o);
            int z0 = Math.max(MathHelper.floor(cz - d), p);
            int x1 = Math.max(MathHelper.floor(cx + d), x0);
            int y1 = Math.max(MathHelper.floor(cy + d), y0);
            int z1 = Math.max(MathHelper.floor(cz + d), z0);
            for (int x = x0; x <= x1; x++) {
                double u = ((double) x + 0.5 - cx) / d;
                if (u * u >= 1.0) continue;
                for (int y = y0; y <= y1; y++) {
                    double v = ((double) y + 0.5 - cy) / d;
                    if (u * u + v * v >= 1.0) continue;
                    for (int z = z0; z <= z1; z++) {
                        double w2 = ((double) z + 0.5 - cz) / d;
                        if (u * u + v * v + w2 * w2 >= 1.0) continue;
                        if (y < s.bottom || y >= s.top) continue;
                        if (!seen.add(BlockPos.asLong(x, y, z))) continue;
                        if (!s.inWindow(x, z)) continue;
                        if (!s.placeable(x, y, z)) continue;
                        boolean place;
                        if (discard <= 0f) place = true;
                        else if (discard >= 1f) place = !s.adjacentToAir(x, y, z);
                        else place = r.nextFloat() >= discard || !s.adjacentToAir(x, y, z);
                        if (place) {
                            s.claimed.add(BlockPos.asLong(x, y, z));
                            s.hits.add(new Hit(x, y, z, f.ore().ordinal(), s.variant(x, y, z)));
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ tick

    private static long ckey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    @Override
    public void onTick() {
        tick++;
        ClientWorld w = mc.world;
        if (w == null) return;
        overworld = w.getRegistryKey() == World.OVERWORLD;
        if (!overworld) {
            shown = new ArrayList<>();
            return;
        }

        String s = seedText.value.trim() + "|" + indexOffset.i();
        if (!s.equals(sig)) {
            sig = s;
            cache.clear();
        }
        long seed = parseSeed(seedText.value);

        Vec3d c = mc.gameRenderer.getCamera().getPos(); // follows Freecam
        int ccx = (int) Math.floor(c.x) >> 4, ccz = (int) Math.floor(c.z) >> 4;
        int r = range.i() + 1; // veins reach into neighbouring chunks, so simulate one ring further

        if (tick % 2 == 0) {
            cache.keySet().removeIf(k -> {
                int cx = (int) (k >> 32), cz = (int) (long) k;
                return Math.abs(cx - ccx) > r + 1 || Math.abs(cz - ccz) > r + 1;
            });
            List<int[]> todo = new ArrayList<>();
            for (int cx = ccx - r; cx <= ccx + r; cx++) {
                for (int cz = ccz - r; cz <= ccz + r; cz++) {
                    Entry e = cache.get(ckey(cx, cz));
                    if (e != null && (e.complete() || tick - e.scannedAt() < 60)) continue;
                    todo.add(new int[]{cx, cz, (cx - ccx) * (cx - ccx) + (cz - ccz) * (cz - ccz)});
                }
            }
            todo.sort(Comparator.<int[]>comparingInt(a -> a[2]));
            int done = 0;
            for (int[] t : todo) {
                if (!anyLoaded(w, t[0], t[1])) continue;
                cache.put(ckey(t[0], t[1]), simulate(w, seed, t[0], t[1], tick));
                if (++done >= 2) break;
            }
        }
        if (tick % 5 == 0) rebuild(w, c, ccx, ccz);
    }

    private static boolean anyLoaded(ClientWorld w, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (w.getChunkManager().getWorldChunk(cx + dx, cz + dz) != null) return true;
            }
        }
        return false;
    }

    private void rebuild(ClientWorld w, Vec3d cam, int ccx, int ccz) {
        int r = range.i();
        List<Hit> all = new ArrayList<>();
        int exp = 0, conf = 0;
        BlockPos.Mutable mp = new BlockPos.Mutable();
        for (Entry e : cache.values()) {
            exp += e.exposed();
            conf += e.confirmed();
            for (Hit h : e.hits()) {
                if (!picks.on(h.variant() * Ore.values().length + h.ore())) continue;
                int cx = h.x() >> 4, cz = h.z() >> 4;
                if (Math.abs(cx - ccx) > r || Math.abs(cz - ccz) > r) continue;
                WorldChunk ch = w.getChunkManager().getWorldChunk(cx, cz);
                if (ch != null) {
                    // drop ores that are gone (mined, cave, water) or sit in something that was never stone
                    BlockState st = ch.getBlockState(mp.set(h.x(), h.y(), h.z()));
                    if (!(st.isIn(BlockTags.BASE_STONE_OVERWORLD) || ALL_ORE_BLOCKS.contains(st.getBlock()))) continue;
                }
                all.add(h);
            }
        }
        int cap = maxBlocks.i();
        if (all.size() > cap) {
            all.sort(Comparator.<Hit>comparingDouble(h -> {
                double dx = h.x() + 0.5 - cam.x, dy = h.y() + 0.5 - cam.y, dz = h.z() + 0.5 - cam.z;
                return dx * dx + dy * dy + dz * dz;
            }));
            all = new ArrayList<>(all.subList(0, cap));
        }
        shown = all;
        shownExposed = exp;
        shownConfirmed = conf;
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
                float x1 = (float) (h.x() - cam.x), y1 = (float) (h.y() - cam.y), z1 = (float) (h.z() - cam.z);
                RenderUtil.boxFill(q, m, x1, y1, z1, x1 + 1f, y1 + 1f, z1 + 1f, ColorUtil.argb(col[h.ore()].rgb(), fa));
            }
            RenderUtil.draw(q);
        }

        BufferBuilder l = RenderUtil.lines(lineWidth.f());
        Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        float sx = (float) (look.x * 0.2), sy = (float) (look.y * 0.2), sz = (float) (look.z * 0.2);
        for (Hit h : list) {
            int c = ColorUtil.argb(col[h.ore()].rgb(), oa);
            float x1 = (float) (h.x() - cam.x), y1 = (float) (h.y() - cam.y), z1 = (float) (h.z() - cam.z);
            RenderUtil.boxLines(l, m, x1, y1, z1, x1 + 1f, y1 + 1f, z1 + 1f, c);
            if (tracers.on()) RenderUtil.line(l, m, sx, sy, sz, x1 + 0.5f, y1 + 0.5f, z1 + 0.5f, c);
        }
        RenderUtil.draw(l);
        RenderUtil.end();
    }

    @Override
    public void renderHud(DrawContext ctx) {
        if (!stats.on() || mc.world == null) return;
        String msg;
        if (!overworld) msg = "Seed Ore ESP: overworld only";
        else if (shownExposed == 0) msg = "Seed Ore ESP: no exposed ores nearby to verify the seed";
        else {
            int pct = Math.round(100f * shownConfirmed / shownExposed);
            msg = "Seed Ore ESP: " + shownConfirmed + "/" + shownExposed + " visible ores match (" + pct + "%)";
            if (shownExposed >= 8 && pct < 40) msg += " - check seed / index offset";
        }
        ctx.drawText(mc.textRenderer, msg, 6, mc.getWindow().getScaledHeight() - 14, 0xFFFFFFFF, true);
    }
}
