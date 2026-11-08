package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.Gfx;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;

/**
 * Spawner Alert: scans the chunks your client has loaded (limited by your render distance) for mob spawners and plays
 * a loud sound when one shows up. Identical sound instances are layered, because a single instance is capped at the
 * master volume.
 */
public final class SpawnerAlert extends Module {
    public enum Tone {
        BELL("Bell"), ANVIL("Anvil"), WITHER("Wither spawn"), THUNDER("Thunder"), DRAGON("Dragon growl"), ORB("XP orb");
        private final String label;
        Tone(String l) { label = l; }
        @Override public String toString() { return label; }

        SoundEvent sound() {
            return switch (this) {
                case BELL -> SoundEvents.BLOCK_BELL_USE;
                case ANVIL -> SoundEvents.BLOCK_ANVIL_LAND;
                case WITHER -> SoundEvents.ENTITY_WITHER_SPAWN;
                case THUNDER -> SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER;
                case DRAGON -> SoundEvents.ENTITY_ENDER_DRAGON_GROWL;
                case ORB -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
            };
        }
    }

    private final Setting.Num range = new Setting.Num("Range (chunks)", 32, 1, 32, 1);
    private final Setting.Choice<Tone> tone = new Setting.Choice<>("Sound", Tone.BELL, Tone.class);
    private final Setting.Num loudness = new Setting.Num("Loudness (layers)", 4, 1, 8, 1);
    private final Setting.Num pitch = new Setting.Num("Pitch", 1.0, 0.5, 2.0, 0.1);
    private final Setting.Num cooldown = new Setting.Num("Cooldown (s)", 3, 1, 30, 1);
    private final Setting.Num repeat = new Setting.Num("Repeat while in range (s, 0 = off)", 0, 0, 30, 1);
    private final Setting.Bool chat = new Setting.Bool("Chat message", true);
    private final Setting.Bool overlay = new Setting.Bool("Action bar message", true);
    private final Setting.Bool hud = new Setting.Bool("HUD indicator", true);

    private Set<Long> known = new HashSet<>();
    private int tick, count;
    private BlockPos nearest;
    private double nearestDist;
    private long lastAlert;

    public SpawnerAlert() {
        super("Spawner Alert", "Plays a loud sound when a spawner is within your render distance.", Category.WORLD);
        add(range, tone, loudness, pitch, cooldown, repeat, chat, overlay, hud);
    }

    private void reset() {
        known = new HashSet<>();
        count = 0;
        nearest = null;
        tick = 0;
    }

    @Override
    public void onEnable() {
        reset();
        lastAlert = 0;
    }

    @Override
    public void onDisable() {
        reset();
    }

    @Override
    public void onWorldChanged() {
        reset();
    }

    @Override
    public void onTick() {
        if (tick++ % 10 != 0) return;
        ClientWorld w = mc.world;
        ClientPlayerEntity pl = mc.player;
        if (w == null || pl == null) return;

        int pcx = pl.getBlockX() >> 4, pcz = pl.getBlockZ() >> 4;
        int r = Math.min(range.i(), mc.options.getClampedViewDistance());

        Set<Long> now = new HashSet<>();
        Set<Long> fresh = new HashSet<>();
        double best = Double.MAX_VALUE;
        BlockPos near = null;
        for (int cx = pcx - r; cx <= pcx + r; cx++) {
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                WorldChunk ch = w.getChunkManager().getWorldChunk(cx, cz);
                if (ch == null) continue;
                for (BlockEntity be : ch.getBlockEntities().values()) {
                    if (!(be instanceof MobSpawnerBlockEntity) || be.isRemoved()) continue;
                    BlockPos p = be.getPos();
                    long key = p.asLong();
                    now.add(key);
                    if (!known.contains(key)) fresh.add(key);
                    double d = pl.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                    if (d < best) {
                        best = d;
                        near = p.toImmutable();
                    }
                }
            }
        }

        count = now.size();
        nearest = near;
        nearestDist = near == null ? 0 : Math.sqrt(best);

        long ms = System.currentTimeMillis();
        boolean newOne = !fresh.isEmpty() && ms - lastAlert >= cooldown.i() * 1000L;
        boolean again = repeat.i() > 0 && count > 0 && ms - lastAlert >= repeat.i() * 1000L;

        // spawners we could not announce yet (cooldown) stay "unknown" so they are announced on a later scan
        if (!newOne) now.removeAll(fresh);
        known = now;

        if (newOne || again) {
            lastAlert = ms;
            alert();
        }
    }

    private void alert() {
        SoundEvent ev = tone.value.sound();
        for (int k = 0; k < loudness.i(); k++) {
            mc.getSoundManager().play(PositionedSoundInstance.master(ev, pitch.f(), 1.0f));
        }
        if (nearest == null || mc.player == null) return;
        Text msg = Text.literal("[NITRO] Spawner detected: " + count + " in range, nearest at "
                + nearest.getX() + " " + nearest.getY() + " " + nearest.getZ()
                + " (" + (int) nearestDist + " blocks)").formatted(Formatting.RED);
        if (chat.on()) mc.player.sendMessage(msg, false);
        if (overlay.on()) mc.inGameHud.setOverlayMessage(msg, false);
    }

    @Override
    public void renderHud(DrawContext ctx) {
        if (!hud.on() || count == 0 || mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;
        String s = "SPAWNER x" + count + "  " + (int) nearestDist + "m";
        int w = tr.getWidth(s) + 16, h = 15;
        int x = ctx.getScaledWindowWidth() / 2 - w / 2, y = 18;
        float blink = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 140.0);
        int rgb = ((int) (255) << 16) | ((int) (70 + 110 * blink) << 8) | 60;
        Gfx.roundFill(ctx, x, y, w, h, 5, 0xB0140A0A);
        Gfx.roundBorder(ctx, x, y, w, h, 5, Gfx.mulA(0xFF000000 | rgb, 0.9f));
        ctx.drawText(tr, s, x + 8, y + 4, 0xFF000000 | rgb, false);
    }
}
