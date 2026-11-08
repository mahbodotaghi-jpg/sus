package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.Gfx;
import dev.nitro.util.Lyrics;
import dev.nitro.util.NowPlaying;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Spotify HUD: animated now-playing card (artwork-tinted, scrolling text, equalizer, progress) plus the current song
 * drawn as big text in the sky. Reads the local Spotify app via {@link NowPlaying}; no login needed.
 */
public final class SpotifyHud extends Module {
    public enum Pos {
        TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right");
        private final String label;
        Pos(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum SkyMode {
        FIXED("Fixed spot"), ORBIT("Slow orbit"), FOLLOW("Drift after view");
        private final String label;
        SkyMode(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum SkyColor {
        ARTWORK("Artwork colours"), RAINBOW("Rainbow"), CUSTOM("Custom");
        private final String label;
        SkyColor(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    private static final Identifier ART_ID = Identifier.of("nitro", "spotify_art");

    // HUD card
    private final Setting.Bool showHud = new Setting.Bool("HUD card", true);
    private final Setting.Choice<Pos> pos = new Setting.Choice<>("Position", Pos.BOTTOM_LEFT, Pos.class);
    private final Setting.Choice<Gfx.PanelStyle> style = new Setting.Choice<>("Style", Gfx.PanelStyle.GLASS, Gfx.PanelStyle.class);
    private final Setting.Num width = new Setting.Num("Width", 200, 150, 300, 5);
    private final Setting.Bool compact = new Setting.Bool("Compact", false);
    private final Setting.Bool showArt = new Setting.Bool("Album artwork", true);
    private final Setting.Num artSize = new Setting.Num("Artwork size", 44, 24, 64, 2);
    private final Setting.Bool showAlbum = new Setting.Bool("Show album", false);
    private final Setting.Bool showProgress = new Setting.Bool("Progress bar", true);
    private final Setting.Bool equalizer = new Setting.Bool("Equalizer animation", true);
    private final Setting.Bool artColour = new Setting.Bool("Accent from artwork", true);
    private final Setting.Bool hideIdle = new Setting.Bool("Hide when nothing plays", true);
    private final Setting.Bool hidePaused = new Setting.Bool("Hide when paused", false);
    private final Setting.Num poll = new Setting.Num("Update interval (s)", 2, 1, 10, 1);
    private final Setting.Color tint = new Setting.Color("Panel tint", 0x20242C);
    private final Setting.Num opacity = new Setting.Num("Panel opacity", 65, 20, 95, 5);
    private final Setting.Color accent = new Setting.Color("Accent", 0x1DB954);

    // song text in the sky
    private final Setting.Bool sky = new Setting.Bool("Song in the sky", true);
    private final Setting.Choice<SkyMode> skyMode = new Setting.Choice<>("Sky: placement", SkyMode.FIXED, SkyMode.class);
    private final Setting.Num skyHeight = new Setting.Num("Sky: height (deg)", 30, 5, 80, 1);
    private final Setting.Num skyOrbit = new Setting.Num("Sky: orbit speed (deg/s)", 4, 0, 30, 1);
    private final Setting.Num skyDist = new Setting.Num("Sky: distance", 100, 40, 200, 10);
    private final Setting.Num skySize = new Setting.Num("Sky: size", 0.8, 0.2, 2.0, 0.05);
    private final Setting.Choice<SkyColor> skyColor = new Setting.Choice<>("Sky: colours", SkyColor.ARTWORK, SkyColor.class);
    private final Setting.Color skyCustom = new Setting.Color("Sky: custom colour", 0x9BE7FF);
    private final Setting.Bool skyProgress = new Setting.Bool("Sky: show progress", true);
    private final Setting.Bool skyDepth = new Setting.Bool("Sky: hide behind terrain", true);

    // lyrics floating in the air
    private final Setting.Bool lyrics = new Setting.Bool("Floating lyrics", true);
    private final Setting.Num lyricSize = new Setting.Num("Lyrics: size", 1.2, 0.4, 3.0, 0.1);
    private final Setting.Num lyricGap = new Setting.Num("Lyrics: gap below title", 22, 0, 80, 2);
    private final Setting.Bool lyricContext = new Setting.Bool("Lyrics: previous / next line", true);
    private final Setting.Num lyricFloat = new Setting.Num("Lyrics: float amount", 2.5, 0, 8, 0.5);
    private final Setting.Num lyricOffset = new Setting.Num("Lyrics: timing offset (ms)", 0, -3000, 3000, 100);

    private final NowPlaying np = new NowPlaying();
    private final Lyrics lyr = new Lyrics();
    private int lastLyricIdx = -2;
    private String lyricKey = "";
    private long lyricT0;
    private int runningInterval = -1;
    private BufferedImage uploadedArt;
    private boolean textureRegistered;

    private int artAccent = 0x1DB954;   // colour taken from the artwork
    private int curAccent = 0x1DB954;   // smoothed colour actually used
    private String lastKey = "";
    private long trackT0;
    private long lastNs, lastWorldNs;
    private float anchorYaw = Float.NaN, followYaw;

    public SpotifyHud() {
        super("Spotify HUD", "Now-playing card and song title in the sky.", Category.HUD);
        add(showHud, pos, style, width, compact, showArt, artSize, showAlbum, showProgress, equalizer, artColour, hideIdle,
                hidePaused, poll, tint, opacity, accent);
        add(sky, skyMode, skyHeight, skyOrbit, skyDist, skySize, skyColor, skyCustom, skyProgress, skyDepth);
        add(lyrics, lyricSize, lyricGap, lyricContext, lyricFloat, lyricOffset);
        lyricGap.showIf(sky::on);
        lyricSize.showIf(lyrics::on);
        lyricContext.showIf(lyrics::on);
        lyricFloat.showIf(lyrics::on);
        lyricOffset.showIf(lyrics::on);
        artSize.showIf(showArt::on);
        showAlbum.showIf(() -> !compact.on());
        pos.showIf(showHud::on);
        skyOrbit.showIf(() -> skyMode.value == SkyMode.ORBIT);
        skyCustom.showIf(() -> skyColor.value == SkyColor.CUSTOM);
    }

    @Override
    public void onEnable() {
        runningInterval = poll.i();
        np.start(runningInterval);
        curAccent = accent.rgb();
        anchorYaw = Float.NaN;
    }

    @Override
    public void onDisable() {
        np.stop();
        lyr.clear();
        runningInterval = -1;
        releaseTexture();
    }

    @Override
    public void onWorldChanged() {
        anchorYaw = Float.NaN;
    }

    @Override
    public void onTick() {
        if (runningInterval != poll.i()) {
            runningInterval = poll.i();
            np.start(runningInterval);
        }
    }

    private void releaseTexture() {
        if (textureRegistered) {
            mc.getTextureManager().destroyTexture(ART_ID);
            textureRegistered = false;
        }
        uploadedArt = null;
        artAccent = accent.rgb();
    }

    /** Render thread: turn the background-loaded BufferedImage into a GPU texture (once per track) and pick its colour. */
    private void syncTexture() {
        BufferedImage a = np.art();
        if (a == uploadedArt) return;
        uploadedArt = a;
        if (a == null) {
            releaseTexture();
            return;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(a, "png", bos);
            NativeImage img = NativeImage.read(new ByteArrayInputStream(bos.toByteArray()));
            mc.getTextureManager().registerTexture(ART_ID, new NativeImageBackedTexture(img));
            textureRegistered = true;
            artAccent = dominant(a);
        } catch (Exception e) {
            releaseTexture();
        }
    }

    /** Vivid average colour of the artwork: saturated, bright pixels count more. */
    private static int dominant(BufferedImage a) {
        double r = 0, g = 0, b = 0, ws = 0;
        for (int y = 0; y < a.getHeight(); y += 2) {
            for (int x = 0; x < a.getWidth(); x += 2) {
                int p = a.getRGB(x, y);
                int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
                int mx = Math.max(pr, Math.max(pg, pb)), mn = Math.min(pr, Math.min(pg, pb));
                double sat = mx == 0 ? 0 : (mx - mn) / (double) mx;
                double w = 0.05 + sat * sat * (mx / 255.0);
                r += pr * w;
                g += pg * w;
                b += pb * w;
                ws += w;
            }
        }
        if (ws <= 0) return 0x1DB954;
        int ar = (int) (r / ws), ag = (int) (g / ws), ab = (int) (b / ws);
        float[] hsb = java.awt.Color.RGBtoHSB(ar, ag, ab, null);
        return ColorUtil.hsv(hsb[0], Math.max(hsb[1], 0.45f), Math.max(hsb[2], 0.85f));
    }

    private int baseAccent() {
        return artColour.on() && textureRegistered ? artAccent : accent.rgb();
    }

    private void trackChanged(NowPlaying.Info i) {
        String k = i == null ? "" : i.key();
        if (!k.equals(lastKey)) {
            lastKey = k;
            trackT0 = System.currentTimeMillis();
        }
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    /** Text that scrolls when it is wider than its box (pauses briefly at the start). */
    private void marquee(DrawContext ctx, TextRenderer tr, String s, int x, int y, int w, int color, float secs) {
        int tw = tr.getWidth(s);
        if (tw <= w) {
            ctx.drawText(tr, s, x, y, color, false);
            return;
        }
        int gap = 24;
        float off = Math.max(0f, secs - 1.5f) * 24f % (tw + gap);
        ctx.enableScissor(x, y - 1, x + w, y + 10);
        ctx.drawText(tr, s, x - (int) off, y, color, false);
        ctx.drawText(tr, s, x - (int) off + tw + gap, y, color, false);
        ctx.disableScissor();
    }

    // ------------------------------------------------------------------ HUD card

    @Override
    public void renderHud(DrawContext ctx) {
        long nowNs = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;

        syncTexture();
        NowPlaying.Info i = np.info();
        trackChanged(i);
        curAccent = ColorUtil.lerp(curAccent, baseAccent(), Math.min(1f, dt * 4f));

        if (mc.options.hudHidden || !showHud.on()) return;
        TextRenderer tr = mc.textRenderer;

        boolean idle = i == null || (i.title.isEmpty() && i.artist.isEmpty());
        if (idle && hideIdle.on()) return;
        if (!idle && !i.playing && hidePaused.on()) return;

        long nowMs = System.currentTimeMillis();
        float time = (nowMs % 1_000_000L) / 1000f;
        float sinceTrack = (nowMs - trackT0) / 1000f;
        float ta = Math.min(1f, sinceTrack / 0.45f);
        float te = ta * ta * (3f - 2f * ta);
        float fa = Math.max(0.05f, te);
        int slide = (int) ((1f - te) * 10f);

        boolean cmp = compact.on();
        int W = width.i();
        int art = showArt.on() ? (cmp ? Math.min(artSize.i(), 30) : artSize.i()) : 0;
        boolean haveArt = art > 0 && textureRegistered && !idle;
        int pad = 8;
        int textX = pad + (art > 0 ? art + 8 : 0);
        int fullW = W - textX - pad;
        boolean eq = equalizer.on() && !idle;
        int titleW = fullW - (eq ? 16 : 0);
        boolean album = !cmp && showAlbum.on() && !idle && !i.album.isEmpty();
        boolean prog = showProgress.on() && !idle && i.lenMs > 0;

        int lines = idle ? 1 : 2 + (album ? 1 : 0);
        int textH = lines * 11 + (prog ? (cmp ? 7 : 15) : 0);
        int H = Math.max(art, textH) + 16;

        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight(), margin = 8;
        boolean right = pos.value == Pos.TOP_RIGHT || pos.value == Pos.BOTTOM_RIGHT;
        boolean top = pos.value == Pos.TOP_LEFT || pos.value == Pos.TOP_RIGHT;
        int x = right ? sw - W - margin : margin;
        int y = top ? margin : sh - H - margin;

        int cur = curAccent;
        int tintMix = artColour.on() && haveArt ? ColorUtil.lerp(tint.rgb(), cur, 0.2f) : tint.rgb();
        Gfx.card(ctx, x, y, W, H, 7, tintMix, opacity.f() / 100f, cur, style.value, 1f, time);

        if (art > 0) {
            int ax = x + pad, ay = y + (H - art) / 2;
            if (haveArt) {
                var ms = ctx.getMatrices();
                ms.push();
                ms.translate(ax, ay, 0);
                float sc = art / (float) NowPlaying.ART_SIZE;
                ms.scale(sc, sc, 1f);
                ctx.drawTexture(RenderLayer::getGuiTextured, ART_ID, 0, 0, 0f, 0f,
                        NowPlaying.ART_SIZE, NowPlaying.ART_SIZE, NowPlaying.ART_SIZE, NowPlaying.ART_SIZE);
                ms.pop();
                if (!i.playing) {
                    ctx.fill(ax, ay, ax + art, ay + art, 0x99000000);
                    int bx = ax + art / 2 - 4, bh = Math.max(6, art / 3), by0 = ay + art / 2 - bh / 2;
                    ctx.fill(bx, by0, bx + 3, by0 + bh, 0xFFFFFFFF);
                    ctx.fill(bx + 5, by0, bx + 8, by0 + bh, 0xFFFFFFFF);
                }
            } else {
                ctx.fill(ax, ay, ax + art, ay + art, ColorUtil.argb(cur, 0.25f));
            }
            Gfx.border(ctx, ax - 1, ay - 1, art + 2, art + 2, ColorUtil.argb(cur, 0.9f));
        }

        int tx = x + textX + slide, ty = y + (H - textH) / 2 + 1;
        if (idle) {
            ctx.drawText(tr, tr.trimToWidth(np.status(), fullW), tx, ty, Gfx.mulA(0xFFB8B8C0, fa), false);
            return;
        }

        marquee(ctx, tr, i.title, tx, ty, titleW, Gfx.mulA(0xFFFFFFFF, fa), sinceTrack);
        if (eq) {
            int ex = x + W - pad - 12, base = y + (H - textH) / 2 + 11;
            for (int b = 0; b < 4; b++) {
                int bh = i.playing ? 2 + (int) (8 * (0.5 + 0.5 * Math.sin(time * (5.0 + b * 1.7) + b * 2.1))) : 2;
                ctx.fill(ex + b * 3, base - bh, ex + b * 3 + 2, base, ColorUtil.argb(cur, 0.95f));
            }
        }
        ty += 11;
        marquee(ctx, tr, i.artist, tx, ty, fullW, Gfx.mulA(0xFFC2C2CC, fa), sinceTrack);
        ty += 11;
        if (album) {
            marquee(ctx, tr, i.album, tx, ty, fullW, Gfx.mulA(0xFF8A8A94, fa), sinceTrack);
            ty += 11;
        }
        if (prog) {
            long curMs = i.estimatedPosMs();
            float frac = Math.max(0f, Math.min(1f, curMs / (float) i.lenMs));
            int by = ty + 2, bx = x + textX;
            Gfx.roundFill(ctx, bx, by, fullW, 3, 1, ColorUtil.argb(0xFFFFFF, 0.16f));
            int fw = (int) (fullW * frac);
            if (fw > 0) ctx.fill(bx, by, bx + fw, by + 3, 0xFF000000 | cur);
            if (!cmp) {
                Gfx.roundFill(ctx, bx + fw - 2, by - 1, 5, 5, 2, 0xFFFFFFFF);
                Gfx.text(ctx, tr, time(curMs), bx, by + 6, 0.75f, 0xFFB8B8C0, false);
                String rt = time(i.lenMs);
                Gfx.text(ctx, tr, rt, bx + fullW - tr.getWidth(rt) * 0.75f, by + 6, 0.75f, 0xFFB8B8C0, false);
            }
        }
    }

    // ------------------------------------------------------------------ song in the sky

    private int skyRgb(int k, float time) {
        return switch (skyColor.value) {
            case RAINBOW -> ColorUtil.hsv(time * 0.12f + k * 0.035f, 0.6f, 1f);
            case CUSTOM -> skyCustom.rgb();
            case ARTWORK -> ColorUtil.lerp(curAccent, 0xFFFFFF, 0.25f + 0.35f * (0.5f + 0.5f * (float) Math.sin(time * 2.2f - k * 0.35f)));
        };
    }

    private Text coloured(String s, float time, boolean bold) {
        MutableText out = Text.empty();
        int[] cps = s.codePoints().toArray();
        for (int k = 0; k < cps.length; k++) {
            out.append(Text.literal(new String(Character.toChars(cps[k])))
                    .setStyle(Style.EMPTY.withColor(skyRgb(k, time)).withBold(bold)));
        }
        return out;
    }

    private void skyLine(MatrixStack ms, TextRenderer tr, VertexConsumerProvider vc, TextRenderer.TextLayerType layer,
                         Text t, float yOff, float lineScale, int argb) {
        float w = tr.getWidth(t);
        float s = lineScale;
        if (w * s > 260f) s = 260f / w;
        ms.push();
        ms.translate(0f, yOff, 0f);
        ms.scale(s, s, 1f);
        Matrix4f m = ms.peek().getPositionMatrix();
        tr.draw(t, -w / 2f, 0f, argb, true, m, vc, layer, 0, 0xF000F0);
        ms.pop();
    }

    @Override
    public void renderWorld(WorldRenderContext ctx) {
        if ((!sky.on() && !lyrics.on()) || mc.world == null) return;
        NowPlaying.Info i = np.info();
        if (i == null || (i.title.isEmpty() && i.artist.isEmpty())) return;
        if (!i.playing && hidePaused.on()) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        Camera cam = ctx.camera();
        trackChanged(i);

        long nowNs = System.nanoTime();
        float dt = lastWorldNs == 0 ? 0.016f : Math.min(0.1f, (nowNs - lastWorldNs) / 1e9f);
        lastWorldNs = nowNs;
        long nowMs = System.currentTimeMillis();
        float time = (nowMs % 1_000_000L) / 1000f;

        float camYaw = cam.getYaw();
        if (Float.isNaN(anchorYaw)) {
            anchorYaw = camYaw;
            followYaw = camYaw;
        }
        float yaw;
        switch (skyMode.value) {
            case ORBIT -> {
                anchorYaw += skyOrbit.f() * dt;
                yaw = anchorYaw;
            }
            case FOLLOW -> {
                followYaw += MathHelper.wrapDegrees(camYaw - followYaw) * Math.min(1f, dt * 1.5f);
                yaw = followYaw;
            }
            default -> yaw = anchorYaw;
        }

        double viewBlocks = mc.options.getClampedViewDistance() * 16.0;
        double dist = Math.min(skyDist.d(), Math.max(32.0, viewBlocks * 0.75));
        Vec3d dir = Vec3d.fromPolar(-skyHeight.f(), yaw); // negative pitch = up
        float scale = skySize.f() * (float) (dist / 100.0);

        float ta = Math.min(1f, (nowMs - trackT0) / 700f);
        int white = Gfx.mulA(0xFFFFFFFF, Math.max(0.05f, ta * ta * (3f - 2f * ta)));

        TextRenderer tr = mc.textRenderer;
        VertexConsumerProvider.Immediate vc = mc.getBufferBuilders().getEntityVertexConsumers();
        TextRenderer.TextLayerType layer = skyDepth.on() ? TextRenderer.TextLayerType.NORMAL : TextRenderer.TextLayerType.SEE_THROUGH;

        ms.push();
        ms.translate(dir.x * dist, dir.y * dist, dir.z * dist);
        ms.multiply(cam.getRotation());          // billboard: always face the camera, like a name tag
        ms.scale(-scale, -scale, scale);

        float y = 0f;
        if (sky.on()) {
            skyLine(ms, tr, vc, layer, coloured(i.title, time, true), y, 1.0f, white);
            y += 12f;
            if (!i.artist.isEmpty()) {
                skyLine(ms, tr, vc, layer, coloured(i.artist, time + 1.3f, false), y, 0.65f, white);
                y += 9f;
            }
            if (skyProgress.on() && i.lenMs > 0) {
                int n = 24;
                int k = Math.round(n * Math.max(0f, Math.min(1f, i.estimatedPosMs() / (float) i.lenMs)));
                String bar = time(i.estimatedPosMs()) + "  [" + "=".repeat(k) + "-".repeat(n - k) + "]  " + time(i.lenMs);
                skyLine(ms, tr, vc, layer, Text.literal(bar), y + 1f, 0.5f, Gfx.mulA(0xFFC8C8D0, Math.max(0.05f, ta)));
                y += 8f;
            }
        }
        if (lyrics.on()) drawLyrics(ms, tr, vc, layer, i, y + (sky.on() ? lyricGap.f() : 0f), time, nowMs);
        vc.draw();
        ms.pop();
    }

    // ------------------------------------------------------------------ floating lyrics

    private void lyricLine(MatrixStack ms, TextRenderer tr, VertexConsumerProvider vc, TextRenderer.TextLayerType layer,
                           Text t, float x, float y, float lineScale, int argb) {
        ms.push();
        ms.translate(x, 0f, 0f);
        skyLine(ms, tr, vc, layer, t, y, lineScale, argb);
        ms.pop();
    }

    private void drawLyrics(MatrixStack ms, TextRenderer tr, VertexConsumerProvider vc, TextRenderer.TextLayerType layer,
                            NowPlaying.Info i, float base, float time, long nowMs) {
        lyr.request(i);
        float sz = lyricSize.f();
        float since = (nowMs - trackT0) / 1000f;
        List<Lyrics.Line> ls = lyr.lines();
        if (ls.isEmpty()) {
            Lyrics.State st = lyr.state();
            if (since < 6f && (st == Lyrics.State.LOADING || st == Lyrics.State.NONE)) {
                String msg = st == Lyrics.State.LOADING ? "loading lyrics..." : "no synced lyrics found";
                lyricLine(ms, tr, vc, layer, Text.literal(msg), 0f, base, 0.5f * sz, Gfx.mulA(0xFFA0A0AA, 0.6f));
            }
            return;
        }

        int idx = lyr.indexAt(i.estimatedPosMs() + lyricOffset.i());
        if (idx != lastLyricIdx || !i.key().equals(lyricKey)) {
            lastLyricIdx = idx;
            lyricKey = i.key();
            lyricT0 = nowMs;
        }
        float la = Math.min(1f, (nowMs - lyricT0) / 380f);
        la = la * la * (3f - 2f * la);
        float fl = lyricFloat.f();

        if (lyricContext.on()) {
            if (idx - 1 >= 0 && !ls.get(idx - 1).text.isEmpty()) {
                float bob = (float) Math.sin(time * 1.1f + 2.0f) * fl * 0.6f;
                lyricLine(ms, tr, vc, layer, Text.literal(ls.get(idx - 1).text), 0f, base - 11f * sz + bob,
                        0.6f * sz, Gfx.mulA(0xFFC8C8D0, 0.35f * (1f - la * 0.4f)));
            }
            if (idx + 1 < ls.size() && !ls.get(idx + 1).text.isEmpty()) {
                float bob = (float) Math.sin(time * 1.2f + 4.0f) * fl * 0.6f;
                lyricLine(ms, tr, vc, layer, Text.literal(ls.get(idx + 1).text), 0f, base + 13f * sz + bob,
                        0.6f * sz, Gfx.mulA(0xFFC8C8D0, 0.4f * la));
            }
        }
        if (idx >= 0 && !ls.get(idx).text.isEmpty()) {
            float bob = (float) Math.sin(time * 1.4f) * fl;
            float sway = (float) Math.sin(time * 0.9f + 1.1f) * fl * 1.6f;
            float rise = (1f - la) * 8f;
            lyricLine(ms, tr, vc, layer, coloured(ls.get(idx).text, time, true), sway, base + bob + rise,
                    1.15f * sz, Gfx.mulA(0xFFFFFFFF, Math.max(0.05f, la)));
        }
    }
}
