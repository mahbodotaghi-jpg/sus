package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.RenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.*;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Storage ESP: outlines loaded storage block entities (chests, barrels, shulker boxes, ...) through walls.
 * Only block entities of chunks the client already has are shown. The list is rebuilt a few times per second.
 */
public final class StorageEsp extends Module {
    private enum Cat {CHEST, TRAPPED, ENDER, BARREL, SHULKER, OTHER, SPAWNER}

    private final Setting.Num range = new Setting.Num("Range (chunks)", 6, 1, 16, 1);
    private final Setting.Bool fChest = new Setting.Bool("Chests", true);
    private final Setting.Bool fTrapped = new Setting.Bool("Trapped chests", true);
    private final Setting.Bool fEnder = new Setting.Bool("Ender chests", true);
    private final Setting.Bool fBarrel = new Setting.Bool("Barrels", true);
    private final Setting.Bool fShulker = new Setting.Bool("Shulker boxes", true);
    private final Setting.Bool fOther = new Setting.Bool("Other containers", false);
    private final Setting.Bool fSpawner = new Setting.Bool("Spawners", true);
    private final Setting.Bool fill = new Setting.Bool("Fill", true);
    private final Setting.Num fillOpacity = new Setting.Num("Fill opacity", 15, 0, 50, 1);
    private final Setting.Num outlineOpacity = new Setting.Num("Outline opacity", 90, 10, 100, 5);
    private final Setting.Num lineWidth = new Setting.Num("Line width", 2, 1, 5, 0.5);
    private final Setting.Bool tracers = new Setting.Bool("Tracers", false);
    private final Setting.Color[] colors = {
            new Setting.Color("Chest colour", 0xE8A33D),
            new Setting.Color("Trapped chest colour", 0xFF4D4D),
            new Setting.Color("Ender chest colour", 0xB77CFF),
            new Setting.Color("Barrel colour", 0xC98B5A),
            new Setting.Color("Shulker colour", 0xFF7AD9),
            new Setting.Color("Other colour", 0x6EA8FF),
            new Setting.Color("Spawner colour", 0x3DFFB0)
    };

    private record Hit(Box box, Cat cat) {}

    private List<Hit> hits = new ArrayList<>();
    private int tick;

    public StorageEsp() {
        super("Storage ESP", "Highlights nearby chests, barrels, shulker boxes, spawners and other containers.", Category.RENDER);
        add(range, fChest, fTrapped, fEnder, fBarrel, fShulker, fOther, fSpawner, fill, fillOpacity, outlineOpacity, lineWidth, tracers);
        add(colors);
        fillOpacity.showIf(fill::on);
    }

    @Override
    public void onEnable() {
        tick = 0;
    }

    @Override
    public void onDisable() {
        hits = new ArrayList<>();
    }

    @Override
    public void onWorldChanged() {
        hits = new ArrayList<>();
    }

    private boolean wanted(Cat c) {
        return switch (c) {
            case CHEST -> fChest.on();
            case TRAPPED -> fTrapped.on();
            case ENDER -> fEnder.on();
            case BARREL -> fBarrel.on();
            case SHULKER -> fShulker.on();
            case OTHER -> fOther.on();
            case SPAWNER -> fSpawner.on();
        };
    }

    private static Cat classify(BlockEntity be) {
        if (be instanceof TrappedChestBlockEntity) return Cat.TRAPPED; // must precede ChestBlockEntity
        if (be instanceof ChestBlockEntity) return Cat.CHEST;
        if (be instanceof EnderChestBlockEntity) return Cat.ENDER;
        if (be instanceof BarrelBlockEntity) return Cat.BARREL;
        if (be instanceof ShulkerBoxBlockEntity) return Cat.SHULKER;
        if (be instanceof MobSpawnerBlockEntity) return Cat.SPAWNER;
        if (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity || be instanceof DropperBlockEntity
                || be instanceof AbstractFurnaceBlockEntity || be instanceof BrewingStandBlockEntity
                || be instanceof ChiseledBookshelfBlockEntity || be instanceof DecoratedPotBlockEntity) return Cat.OTHER;
        return null;
    }

    @Override
    public void onTick() {
        if (tick++ % 6 != 0) return;
        ClientWorld w = mc.world;
        if (w == null) return;

        Vec3d c = mc.gameRenderer.getCamera().getPos(); // follows Freecam
        int ccx = (int) Math.floor(c.x) >> 4, ccz = (int) Math.floor(c.z) >> 4;
        int r = range.i();

        List<Hit> out = new ArrayList<>();
        for (int cx = ccx - r; cx <= ccx + r; cx++) {
            for (int cz = ccz - r; cz <= ccz + r; cz++) {
                WorldChunk ch = w.getChunkManager().getWorldChunk(cx, cz);
                if (ch == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> e : ch.getBlockEntities().entrySet()) {
                    BlockEntity be = e.getValue();
                    if (be == null || be.isRemoved()) continue;
                    Cat cat = classify(be);
                    if (cat == null || !wanted(cat)) continue;
                    BlockPos p = e.getKey();
                    BlockState st = be.getCachedState();
                    VoxelShape sh = st.getOutlineShape(w, p);
                    Box b = sh.isEmpty() ? new Box(p) : sh.getBoundingBox().offset(p);
                    out.add(new Hit(b, cat));
                }
            }
        }
        hits = out;
    }

    @Override
    public void renderWorld(WorldRenderContext ctx) {
        List<Hit> list = hits;
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
                Box b = h.box();
                RenderUtil.boxFill(q, m, (float) (b.minX - cam.x), (float) (b.minY - cam.y), (float) (b.minZ - cam.z),
                        (float) (b.maxX - cam.x), (float) (b.maxY - cam.y), (float) (b.maxZ - cam.z),
                        ColorUtil.argb(colors[h.cat().ordinal()].rgb(), fa));
            }
            RenderUtil.draw(q);
        }

        BufferBuilder l = RenderUtil.lines(lineWidth.f());
        Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        float sx = (float) (look.x * 0.2), sy = (float) (look.y * 0.2), sz = (float) (look.z * 0.2);
        for (Hit h : list) {
            Box b = h.box();
            int col = ColorUtil.argb(colors[h.cat().ordinal()].rgb(), oa);
            float x1 = (float) (b.minX - cam.x), y1 = (float) (b.minY - cam.y), z1 = (float) (b.minZ - cam.z);
            float x2 = (float) (b.maxX - cam.x), y2 = (float) (b.maxY - cam.y), z2 = (float) (b.maxZ - cam.z);
            RenderUtil.boxLines(l, m, x1, y1, z1, x2, y2, z2, col);
            if (tracers.on()) RenderUtil.line(l, m, sx, sy, sz, (x1 + x2) / 2f, (y1 + y2) / 2f, (z1 + z2) / 2f, col);
        }
        RenderUtil.draw(l);
        RenderUtil.end();
    }
}
