package dev.nitro.gui;

import dev.nitro.Config;
import dev.nitro.module.Module;
import dev.nitro.module.ModuleManager;
import dev.nitro.settings.Setting;
import dev.nitro.util.ColorUtil;
import dev.nitro.util.Gfx;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NITRO ClickGUI: animated violet-to-cyan accent, fade/slide-in, category tabs with a sliding highlight,
 * searchable module cards with animated switches, and a settings pane with sliders, chips and colour bars.
 */
public final class ClickGui extends Screen {
    private static final int TAB_W = 104, LIST_W = 200, HEAD = 46, CARD_H = 38, GAP = 4;

    private int catIndex = 1; // 0 = favourites
    private Module selected;
    private int listScroll, setScroll;
    private boolean binding;
    private Setting.Str textFocus;
    private String search = "";
    private boolean searchFocus;

    private Setting.Num dragNum;
    private int dragX0, dragW;
    private Setting.Color dragColor;
    private int dragCh;

    private final Map<Object, Float> anim = new HashMap<>();
    private final long openedAt = System.nanoTime();
    private long lastFrame = openedAt;
    private float time, dt, alpha, slide, tabY = -1;

    private int wx, wy, ww, wh;

    private enum Kind { ENABLE, BIND, FAV, SETTING }

    private record Row(Kind kind, Setting<?> s, int y, int h) {}

    public ClickGui() {
        super(Text.literal("NITRO"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------ geometry

    private void layout() {
        time = (System.nanoTime() - openedAt) / 1e9f;
        float p = Math.min(1f, time / 0.28f);
        float e = 1f - (1f - p) * (1f - p) * (1f - p);
        alpha = e;
        slide = (1f - e) * 16f;
        ww = Math.min(width - 20, 660);
        wh = Math.min(height - 20, 370);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2 + Math.round(slide);
    }

    private int listX() { return wx + TAB_W; }
    private int setX() { return listX() + LIST_W + 6; }
    private int setR() { return wx + ww - 10; }
    private int top() { return wy + HEAD; }
    private int bot() { return wy + wh - 10; }
    private int searchX() { return wx + ww - 16 - 190; }

    // ------------------------------------------------------------ colour helpers

    private int accent(float t) {
        float phase = Theme.ANIMATED.on() ? time * 1.1f : 0f;
        float s = 0.5f + 0.5f * (float) Math.sin(phase + t * 2.6f);
        return ColorUtil.lerp(Theme.a1(), Theme.a2(), s);
    }

    private int ac(float t, float a) {
        return ColorUtil.argb(accent(t), a * alpha);
    }

    private int A(int argb) {
        return Gfx.mulA(argb, alpha);
    }

    private float an(Object key, boolean on) {
        float cur = anim.getOrDefault(key, on ? 1f : 0f);
        float target = on ? 1f : 0f;
        cur += (target - cur) * Math.min(1f, dt * 14f);
        if (Math.abs(target - cur) < 0.005f) cur = target;
        anim.put(key, cur);
        return cur;
    }

    private void text(DrawContext c, String s, int x, int y, int argb) {
        if (((argb >>> 24) & 0xFF) < 8) return;
        c.drawText(textRenderer, s, x, y, argb, false);
    }

    private void small(DrawContext c, String s, float x, float y, float scale, int argb) {
        if (((argb >>> 24) & 0xFF) < 8) return;
        Gfx.text(c, textRenderer, s, x, y, scale, argb, false);
    }

    private void sw(DrawContext c, int x, int y, Object key, boolean on) {
        float t = an(key, on);
        int track = Gfx.lerpArgb(A(Theme.track()), ac(x / 120f, 1f), t);
        Gfx.roundFill(c, x, y, 26, 14, 7, track);
        int kx = x + 2 + Math.round(12 * t);
        Gfx.roundFill(c, kx, y + 2, 10, 10, 5, A(0xFFF8F8FC));
    }

    // ------------------------------------------------------------ content model

    private List<Module> visibleModules() {
        List<Module> out = new ArrayList<>();
        Module.Category[] cats = Module.Category.values();
        String q = search.trim().toLowerCase(Locale.ROOT);
        for (Module m : ModuleManager.INSTANCE.modules) {
            boolean ok;
            if (!q.isEmpty()) {
                ok = m.name.toLowerCase(Locale.ROOT).contains(q) || m.description.toLowerCase(Locale.ROOT).contains(q);
            } else {
                ok = catIndex == 0 ? m.favorite : catIndex <= cats.length && m.category == cats[catIndex - 1];
            }
            if (ok) out.add(m);
        }
        return out;
    }

    private int themeTab() {
        return Module.Category.values().length + 1;
    }

    private int tabCount() {
        return themeTab() + 1;
    }

    private boolean onThemes() {
        return catIndex == themeTab() && search.trim().isEmpty();
    }

    private int listCount() {
        return onThemes() ? Theme.Preset.values().length : visibleModules().size();
    }

    private int enabledIn(int tab) {
        if (tab == themeTab()) return 0;
        int n = 0;
        Module.Category[] cats = Module.Category.values();
        for (Module m : ModuleManager.INSTANCE.modules) {
            boolean in = tab == 0 ? m.favorite : m.category == cats[tab - 1];
            if (in && m.enabled) n++;
        }
        return n;
    }

    private int rowHeight(Setting<?> s) {
        if (s instanceof Setting.Num) return 36;
        if (s instanceof Setting.Color) return 44;
        if (s instanceof Setting.Str) return 44;
        if (s instanceof Setting.BlockSet) return BS_H;
        if (s instanceof Setting.Picks p) {
            int cols = pickCols(p, setR() - setX() - 24);
            return 22 + ((p.icons.length + cols - 1) / cols) * 24 + 4;
        }
        if (s instanceof Setting.Choice<?> c) return 24 + c.options().length * 15;
        return 26;
    }

    private static final int BS_H = 22 + 22 + Setting.BlockSet.ROWS * 24 + 24;

    private static int bsCols(int iw) {
        return Math.max(1, (iw - 16) / 24);
    }

    private static int pickCols(Setting.Picks p, int iw) {
        return Math.max(1, Math.min(p.perRow, (iw - 16) / 24));
    }

    private int headerHeight() {
        if (selected == null) return 0;
        int lines = textRenderer.wrapLines(Text.literal(selected.description), setR() - setX() - 28).size();
        return 30 + lines * 10 + 8;
    }

    private String themeDesc() {
        return Theme.PRESET.value.blurb + ". Changes apply instantly and are saved with your config.";
    }

    private int themeHeaderHeight() {
        int lines = textRenderer.wrapLines(Text.literal(themeDesc()), setR() - setX() - 28).size();
        return 30 + lines * 10 + 8 + 14;
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (onThemes()) {
            int y = top() + 6 + themeHeaderHeight() - setScroll;
            for (Setting<?> s : Theme.settings()) {
                if (!s.visible.getAsBoolean()) continue;
                int h = rowHeight(s);
                rows.add(new Row(Kind.SETTING, s, y, h));
                y += h + GAP;
            }
            return rows;
        }
        if (selected == null) return rows;
        int y = top() + 6 + headerHeight() - setScroll;
        rows.add(new Row(Kind.ENABLE, null, y, 28)); y += 32;
        rows.add(new Row(Kind.BIND, null, y, 26)); y += 30;
        rows.add(new Row(Kind.FAV, null, y, 26)); y += 34;
        for (Setting<?> s : selected.settings) {
            if (!s.visible.getAsBoolean()) continue;
            int h = rowHeight(s);
            rows.add(new Row(Kind.SETTING, s, y, h));
            y += h + GAP;
        }
        return rows;
    }

    private int contentHeight() {
        List<Row> r = rows();
        if (r.isEmpty()) return 0;
        Row last = r.get(r.size() - 1);
        return last.y() + last.h() + setScroll - top();
    }

    // ------------------------------------------------------------ render

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        layout();
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;

        ctx.fillGradient(0, 0, width, height, A(Theme.backTop()), A(Theme.backBot()));

        // window
        Gfx.glow(ctx, wx, wy, ww, wh, 12, accent(0.4f), 0.32f * alpha);
        Gfx.roundGradientV(ctx, wx, wy, ww, wh, 12, A(Theme.winTop()), A(Theme.winBot()));
        Gfx.roundBorder(ctx, wx, wy, ww, wh, 12, ac(0.2f, 0.55f));
        for (int x = wx + 12; x < wx + ww - 12; x += 4) {
            ctx.fill(x, wy + 1, Math.min(x + 4, wx + ww - 12), wy + 3, ac((x - wx) / (float) ww, 0.95f));
        }

        drawHeader(ctx, mx, my);
        drawTabs(ctx, mx, my);
        drawList(ctx, mx, my);
        drawSettings(ctx, mx, my);
    }

    private void drawHeader(DrawContext ctx, int mx, int my) {
        // gradient logo
        String logo = "NITRO";
        float x = wx + 18;
        for (int i = 0; i < logo.length(); i++) {
            String ch = String.valueOf(logo.charAt(i));
            small(ctx, ch, x, wy + 13, 1.6f, ac(i / 4f, 1f));
            x += textRenderer.getWidth(ch) * 1.6f + 1f;
        }
        small(ctx, "CLIENT", x + 4, wy + 19, 0.75f, A(Theme.dim()));

        // search
        int sx = searchX(), sy = wy + 12, sw = 190, sh = 20;
        float f = an("search", searchFocus);
        Gfx.roundFill(ctx, sx, sy, sw, sh, 6, A(0x66000000));
        Gfx.roundBorder(ctx, sx, sy, sw, sh, 6, Gfx.lerpArgb(A(0x33FFFFFF), ac(0.6f, 0.9f), f));
        boolean empty = search.isEmpty();
        String shown = empty ? (searchFocus ? "" : "Search modules...") : search;
        while (textRenderer.getWidth(shown) > sw - 16) shown = shown.substring(1);
        text(ctx, shown, sx + 8, sy + 6, A(empty ? Theme.faint() : Theme.text()));
        if (searchFocus && (int) (time * 2) % 2 == 0) {
            int cx = sx + 8 + textRenderer.getWidth(shown);
            ctx.fill(cx, sy + 5, cx + 1, sy + 15, A(0xFFFFFFFF));
        }
    }

    private void drawTabs(DrawContext ctx, int mx, int my) {
        Module.Category[] cats = Module.Category.values();
        int n = tabCount();
        boolean searching = !search.trim().isEmpty();
        int tx = wx + 10, tw = TAB_W - 16;

        float target = top() + 8 + catIndex * 30;
        tabY = tabY < 0 ? target : tabY + (target - tabY) * Math.min(1f, dt * 16f);
        if (!searching) {
            Gfx.roundFill(ctx, tx, Math.round(tabY), tw, 26, 7, ac(0.1f, 0.22f));
            Gfx.roundFill(ctx, tx, Math.round(tabY) + 5, 3, 16, 1, ac(0.0f, 1f));
        }
        for (int i = 0; i < n; i++) {
            int ty = top() + 8 + i * 30;
            boolean sel = i == catIndex && !searching;
            boolean hover = mx >= tx && mx < tx + tw && my >= ty && my < ty + 26;
            String label = i == 0 ? "Favorites" : i == themeTab() ? "Themes" : cats[i - 1].label;
            int col = sel ? A(Theme.text()) : A(hover ? Theme.soft() : Theme.dim());
            text(ctx, label, tx + 12, ty + 9, col);
            int cnt = enabledIn(i);
            if (cnt > 0) {
                String s = String.valueOf(cnt);
                Gfx.roundFill(ctx, tx + tw - 18, ty + 7, 14, 12, 6, ac(i / 5f, 0.35f));
                small(ctx, s, tx + tw - 11 - textRenderer.getWidth(s) * 0.4f, ty + 10, 0.8f, A(Theme.text()));
            }
        }
        // footer hint
        small(ctx, "RShift: close", tx + 4, bot() - 8, 0.75f, A(Theme.faint()));
    }

    private void drawThemeList(DrawContext ctx, int mx, int my) {
        int lx = listX() + 2, lw = LIST_W - 4;
        Gfx.roundFill(ctx, lx, top(), lw, bot() - top(), 9, A(0x55000000));
        ctx.enableScissor(lx, top() + 1, lx + lw, bot() - 1);
        int y = top() + 6 - listScroll;
        for (Theme.Preset p : Theme.Preset.values()) {
            int cx = lx + 6, cw = lw - 12;
            boolean sel = Theme.PRESET.value == p;
            boolean hover = mx >= cx && mx < cx + cw && my >= y && my < y + CARD_H && my >= top() && my < bot();
            float on = an("theme#" + p.name(), sel);
            float hv = an("theme#h" + p.name(), hover);
            int base = Gfx.lerpArgb(A(0x33FFFFFF), ac(0.3f, 0.20f), on);
            base = Gfx.lerpArgb(base, A(0x4DFFFFFF), hv * 0.5f);
            Gfx.roundFill(ctx, cx, y, cw, CARD_H, 8, base);
            if (sel) Gfx.roundBorder(ctx, cx, y, cw, CARD_H, 8, ac(0.5f, 0.9f));
            if (on > 0.02f) Gfx.roundFill(ctx, cx, y + 6, 3, CARD_H - 12, 1, ac(y / 140f, on));
            text(ctx, p.label, cx + 10, y + 6, A(Gfx.lerpArgb(Theme.soft(), 0xFFFFFFFF, on)));
            String desc = textRenderer.trimToWidth(p.blurb, (int) ((cw - 76) / 0.75f));
            small(ctx, desc, cx + 10, y + 21, 0.75f, A(Theme.dim()));
            int[] dots = Theme.preview(p);
            for (int i = 0; i < 3; i++) {
                int dx = cx + cw - 52 + i * 14;
                Gfx.roundFill(ctx, dx, y + 13, 12, 12, 6, A(0xFF000000 | dots[i]));
                Gfx.roundBorder(ctx, dx, y + 13, 12, 12, 6, A(0x66FFFFFF));
            }
            y += CARD_H + GAP;
        }
        ctx.disableScissor();
    }

    private void drawThemePane(DrawContext ctx, int mx, int my) {
        int sx = setX(), sw = setR() - sx;
        Gfx.roundFill(ctx, sx, top(), sw, bot() - top(), 9, A(0x55000000));
        ctx.enableScissor(sx, top() + 1, sx + sw, bot() - 1);
        int ix = sx + 12, iw = sw - 24;
        int hy = top() + 8 - setScroll;
        small(ctx, Theme.PRESET.value.label + " theme", ix, hy + 2, 1.5f, A(0xFFFFFFFF));
        int ly = hy + 28;
        for (OrderedText line : textRenderer.wrapLines(Text.literal(themeDesc()), iw - 4)) {
            ctx.drawText(textRenderer, line, ix, ly, A(Theme.dim()), false);
            ly += 10;
        }
        for (int x = 0; x < iw; x += 2) {
            int col = 0xFF000000 | ColorUtil.lerp(Theme.a1(), Theme.a2(), x / (float) iw);
            ctx.fill(ix + x, ly + 4, Math.min(ix + x + 2, ix + iw), ly + 10, A(col));
        }
        for (Row r : rows()) {
            if (r.y() + r.h() < top() || r.y() > bot()) continue;
            drawSetting(ctx, r, ix, iw, mx, my);
        }
        ctx.disableScissor();
    }

    private void drawList(DrawContext ctx, int mx, int my) {
        if (onThemes()) {
            drawThemeList(ctx, mx, my);
            return;
        }
        int lx = listX() + 2, lw = LIST_W - 4;
        Gfx.roundFill(ctx, lx, top(), lw, bot() - top(), 9, A(0x55000000));
        ctx.enableScissor(lx, top() + 1, lx + lw, bot() - 1);
        List<Module> mods = visibleModules();
        int y = top() + 6 - listScroll;
        if (mods.isEmpty()) {
            String msg = !search.trim().isEmpty() ? "No match" : catIndex == 0 ? "Star a module to pin it here" : "No modules";
            text(ctx, msg, lx + 12, y + 6, A(Theme.dim()));
        }
        for (Module m : mods) {
            int cx = lx + 6, cw = lw - 12;
            boolean sel = m == selected;
            boolean hover = mx >= cx && mx < cx + cw && my >= y && my < y + CARD_H && my >= top() && my < bot();
            float on = an(m, m.enabled);
            float hv = an(m.name + "#h", hover);
            int base = Gfx.lerpArgb(A(0x33FFFFFF), ac(0.3f, 0.20f), on);
            base = Gfx.lerpArgb(base, A(0x4DFFFFFF), hv * 0.5f);
            Gfx.roundFill(ctx, cx, y, cw, CARD_H, 8, base);
            if (sel) Gfx.roundBorder(ctx, cx, y, cw, CARD_H, 8, ac(0.5f, 0.9f));
            if (on > 0.02f) Gfx.roundFill(ctx, cx, y + 6, 3, CARD_H - 12, 1, ac(y / 140f, on));

            text(ctx, m.favorite ? "*" : "+", cx + 9, y + 6, m.favorite ? ac(0.1f, 1f) : A(Theme.faint()));
            text(ctx, m.name, cx + 20, y + 6, A(Gfx.lerpArgb(Theme.soft(), 0xFFFFFFFF, on)));
            String desc = textRenderer.trimToWidth(m.description, (int) ((cw - 66) / 0.75f));
            small(ctx, desc, cx + 20, y + 21, 0.75f, A(Theme.dim()));
            sw(ctx, cx + cw - 34, y + 12, m, m.enabled);
            y += CARD_H + GAP;
        }
        ctx.disableScissor();
    }

    private void drawSettings(DrawContext ctx, int mx, int my) {
        if (onThemes()) {
            drawThemePane(ctx, mx, my);
            return;
        }
        int sx = setX(), sw = setR() - sx;
        Gfx.roundFill(ctx, sx, top(), sw, bot() - top(), 9, A(0x55000000));
        if (selected == null) {
            text(ctx, "Select a module", sx + 16, top() + 16, A(Theme.dim()));
            text(ctx, "Click a card to see its settings", sx + 16, top() + 30, A(Theme.faint()));
            return;
        }
        ctx.enableScissor(sx, top() + 1, sx + sw, bot() - 1);
        int ix = sx + 12, iw = sw - 24;

        // header: name + description
        int hy = top() + 8 - setScroll;
        small(ctx, selected.name, ix, hy + 2, 1.5f, A(0xFFFFFFFF));
        int ly = hy + 28;
        for (OrderedText line : textRenderer.wrapLines(Text.literal(selected.description), iw - 4)) {
            ctx.drawText(textRenderer, line, ix, ly, A(Theme.dim()), false);
            ly += 10;
        }

        for (Row r : rows()) {
            if (r.y() + r.h() < top() || r.y() > bot()) continue;
            switch (r.kind()) {
                case ENABLE -> {
                    float t = an(selected.name + "#big", selected.enabled);
                    int c1 = Gfx.lerpArgb(A(0x44FFFFFF), ac(0.1f, 0.9f), t);
                    int c2 = Gfx.lerpArgb(A(0x33FFFFFF), ac(0.9f, 0.9f), t);
                    Gfx.roundGradientV(ctx, ix, r.y(), iw, r.h(), 8, c1, c2);
                    String s = selected.enabled ? "ENABLED" : "DISABLED";
                    text(ctx, s, ix + iw / 2 - textRenderer.getWidth(s) / 2, r.y() + 10, A(0xFFFFFFFF));
                }
                case BIND -> {
                    card(ctx, ix, r, iw);
                    text(ctx, "Shortcut", ix + 10, r.y() + 9, A(Theme.text()));
                    String k = binding ? "press a key..." : keyName(selected.key);
                    int w = textRenderer.getWidth(k) + 14;
                    Gfx.roundFill(ctx, ix + iw - w - 6, r.y() + 4, w, 18, 5, binding ? ac(0.2f, 0.35f) : A(0x44000000));
                    text(ctx, k, ix + iw - w - 6 + 7, r.y() + 9, binding ? A(0xFFFFFFFF) : A(Theme.dim()));
                }
                case FAV -> {
                    card(ctx, ix, r, iw);
                    text(ctx, "Favorite", ix + 10, r.y() + 9, A(Theme.text()));
                    sw(ctx, ix + iw - 36, r.y() + 6, "fav", selected.favorite);
                }
                case SETTING -> drawSetting(ctx, r, ix, iw, mx, my);
            }
        }
        ctx.disableScissor();
    }

    private void card(DrawContext ctx, int ix, Row r, int iw) {
        Gfx.roundFill(ctx, ix, r.y(), iw, r.h(), 8, A(0x33FFFFFF));
    }

    private void drawSetting(DrawContext ctx, Row r, int ix, int iw, int mx, int my) {
        Setting<?> s = r.s();
        int y = r.y();
        card(ctx, ix, r, iw);
        text(ctx, s.name, ix + 10, y + 9, A(Theme.text()));
        if (s instanceof Setting.Bool b) {
            sw(ctx, ix + iw - 36, y + 6, s, b.on());
        } else if (s instanceof Setting.Choice<?> c) {
            Object[] opts = c.options();
            for (int i = 0; i < opts.length; i++) {
                boolean on = opts[i] == c.value;
                int oy = y + 22 + i * 15;
                float t = an(c.name + "#" + System.identityHashCode(c) + i, on);
                if (t > 0.02f) Gfx.roundFill(ctx, ix + 8, oy, iw - 16, 13, 5, ac(i / 4f, 0.30f * t));
                text(ctx, String.valueOf(opts[i]), ix + 16, oy + 3, A(on ? 0xFFFFFFFF : Theme.dim()));
                if (on) Gfx.roundFill(ctx, ix + iw - 20, oy + 4, 5, 5, 2, ac(i / 4f, 1f));
            }
        } else if (s instanceof Setting.Num n) {
            String v = n.step >= 1 ? String.valueOf(n.i()) : String.format("%.2f", n.d()).replaceAll("0+$", "").replaceAll("\\.$", "");
            text(ctx, v, ix + iw - 10 - textRenderer.getWidth(v), y + 9, A(0xFFFFFFFF));
            int x0 = ix + 10, w = iw - 20;
            Gfx.roundFill(ctx, x0, y + 25, w, 4, 2, A(Theme.track()));
            int fw = Math.max(4, (int) (w * n.fraction()));
            Gfx.roundFill(ctx, x0, y + 25, fw, 4, 2, ac(0.2f, 1f));
            Gfx.roundFill(ctx, x0 + fw - 4, y + 23, 8, 8, 4, A(0xFFFFFFFF));
        } else if (s instanceof Setting.BlockSet bs) {
            drawBlockSet(ctx, bs, y, ix, iw, mx, my);
        } else if (s instanceof Setting.Picks p) {
            int cols = pickCols(p, iw);
            int hover = -1;
            for (int i = 0; i < p.icons.length; i++) {
                int cx = ix + 8 + (i % cols) * 24, cy = y + 22 + (i / cols) * 24;
                boolean sel = p.on(i);
                boolean hv = mx >= cx && mx < cx + 22 && my >= cy && my < cy + 22;
                if (hv) hover = i;
                float t = an("pick#" + System.identityHashCode(p) + "#" + i, sel);
                int bg = Gfx.lerpArgb(A(0x22FFFFFF), ac(i / 8f, 0.35f), t);
                if (hv) bg = Gfx.lerpArgb(bg, A(0x44FFFFFF), 0.5f);
                Gfx.roundFill(ctx, cx, cy, 22, 22, 6, bg);
                if (t > 0.02f) Gfx.roundBorder(ctx, cx, cy, 22, 22, 6, ac(i / 8f, 0.9f * t));
                ctx.drawItem(new ItemStack(p.icons[i]), cx + 3, cy + 3);
            }
            String hl = hover >= 0 ? p.labels[hover] : p.count() + " selected";
            text(ctx, hl, ix + iw - 10 - textRenderer.getWidth(hl), y + 9, A(hover >= 0 ? Theme.text() : Theme.dim()));
        } else if (s instanceof Setting.Str t) {
            int bx = ix + 8, by = y + 22, bw = iw - 16, bh = 16;
            boolean f = textFocus == t;
            float fa = an("txt#" + System.identityHashCode(t), f);
            Gfx.roundFill(ctx, bx, by, bw, bh, 5, A(0x66000000));
            Gfx.roundBorder(ctx, bx, by, bw, bh, 5, Gfx.lerpArgb(A(0x33FFFFFF), ac(0.6f, 0.9f), fa));
            boolean empty = t.value.isEmpty();
            String shown;
            if (empty) {
                shown = f ? "" : textRenderer.trimToWidth(t.hint, bw - 12);
            } else {
                shown = t.value;
                while (!shown.isEmpty() && textRenderer.getWidth(shown) > bw - 14) shown = shown.substring(1);
            }
            text(ctx, shown, bx + 6, by + 4, A(empty ? Theme.faint() : Theme.text()));
            if (f && (int) (time * 2) % 2 == 0) {
                int cx = bx + 6 + textRenderer.getWidth(shown);
                ctx.fill(cx, by + 3, cx + 1, by + 13, A(0xFFFFFFFF));
            }
        } else if (s instanceof Setting.Color c) {
            Gfx.roundFill(ctx, ix + iw - 30, y + 6, 20, 12, 4, A(0xFF000000 | c.rgb()));
            Gfx.roundBorder(ctx, ix + iw - 30, y + 6, 20, 12, 4, A(0x66FFFFFF));
            int x0 = ix + 10, w = (iw - 20) / 3;
            int[] cols = {0xFFE05555, 0xFF55C46A, 0xFF5587E0};
            for (int i = 0; i < 3; i++) {
                int sx = x0 + i * w;
                Gfx.roundFill(ctx, sx, y + 28, w - 6, 4, 2, A(Theme.track()));
                int fw = Math.max(4, (int) ((w - 6) * (c.ch(i) / 255.0)));
                Gfx.roundFill(ctx, sx, y + 28, fw, 4, 2, A(cols[i]));
                Gfx.roundFill(ctx, sx + fw - 4, y + 26, 8, 8, 4, A(0xFFFFFFFF));
            }
        }
    }

    private void chip(DrawContext ctx, String label, int x, int y, int w, boolean active, boolean hv) {
        int bg = Gfx.lerpArgb(A(0x22FFFFFF), ac(0.4f, 0.35f), active ? 1f : 0f);
        if (hv) bg = Gfx.lerpArgb(bg, A(0x44FFFFFF), 0.5f);
        Gfx.roundFill(ctx, x, y, w, 16, 5, bg);
        text(ctx, label, x + (w - textRenderer.getWidth(label)) / 2, y + 4, A(active ? 0xFFFFFFFF : Theme.soft()));
    }

    private void drawBlockSet(DrawContext ctx, Setting.BlockSet bs, int y, int ix, int iw, int mx, int my) {
        java.util.List<net.minecraft.block.Block> list = bs.list();
        int cols = bsCols(iw);
        int perPage = cols * Setting.BlockSet.ROWS;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        bs.page = Math.max(0, Math.min(pages - 1, bs.page));

        // search box
        int bx = ix + 8, by = y + 22, bw = iw - 16;
        boolean f = textFocus == bs.search;
        float fa = an("bsearch#" + System.identityHashCode(bs), f);
        Gfx.roundFill(ctx, bx, by, bw, 16, 5, A(0x66000000));
        Gfx.roundBorder(ctx, bx, by, bw, 16, 5, Gfx.lerpArgb(A(0x33FFFFFF), ac(0.6f, 0.9f), fa));
        boolean empty = bs.search.value.isEmpty();
        String shown = empty ? (f ? "" : bs.search.hint) : bs.search.value;
        while (!shown.isEmpty() && textRenderer.getWidth(shown) > bw - 14) shown = empty ? shown.substring(0, shown.length() - 1) : shown.substring(1);
        text(ctx, shown, bx + 6, by + 4, A(empty ? Theme.faint() : Theme.text()));
        if (f && (int) (time * 2) % 2 == 0) {
            int cx = bx + 6 + textRenderer.getWidth(shown);
            ctx.fill(cx, by + 3, cx + 1, by + 13, A(0xFFFFFFFF));
        }

        // grid
        int gy = y + 44;
        int hover = -1;
        int start = bs.page * perPage;
        for (int k = 0; k < perPage && start + k < list.size(); k++) {
            net.minecraft.block.Block b = list.get(start + k);
            int cx = ix + 8 + (k % cols) * 24, cy = gy + (k / cols) * 24;
            boolean sel = bs.has(b);
            boolean hv = mx >= cx && mx < cx + 22 && my >= cy && my < cy + 22;
            if (hv) hover = start + k;
            int bg = sel ? ac(k / 8f, 0.35f) : A(0x22FFFFFF);
            if (hv) bg = Gfx.lerpArgb(bg, A(0x44FFFFFF), 0.5f);
            Gfx.roundFill(ctx, cx, cy, 22, 22, 6, bg);
            if (sel) Gfx.roundBorder(ctx, cx, cy, 22, 22, 6, ac(k / 8f, 0.9f));
            ctx.drawItem(new ItemStack(b), cx + 3, cy + 3);
        }
        if (list.isEmpty()) text(ctx, "No blocks match", ix + 12, gy + 6, A(Theme.dim()));

        // header: hovered name or selection count
        String hl = hover >= 0 ? bs.nameOf(list.get(hover)) : bs.value.size() + " selected / " + Setting.BlockSet.totalBlocks();
        text(ctx, textRenderer.trimToWidth(hl, iw - 20 - textRenderer.getWidth(bs.name)), ix + iw - 10 - textRenderer.getWidth(textRenderer.trimToWidth(hl, iw - 20 - textRenderer.getWidth(bs.name))), y + 9, A(hover >= 0 ? Theme.text() : Theme.dim()));

        // pager
        int py = gy + Setting.BlockSet.ROWS * 24 + 4;
        chip(ctx, "< Prev", ix + 8, py, 40, false, mx >= ix + 8 && mx < ix + 48 && my >= py && my < py + 16);
        chip(ctx, "Next >", ix + 52, py, 40, false, mx >= ix + 52 && mx < ix + 92 && my >= py && my < py + 16);
        text(ctx, (bs.page + 1) + " / " + pages, ix + 98, py + 4, A(Theme.dim()));
        String so = "Selected only";
        int sw2 = textRenderer.getWidth(so) + 14;
        int sx2 = ix + iw - 8 - sw2;
        chip(ctx, so, sx2, py, sw2, bs.selectedOnly, mx >= sx2 && mx < sx2 + sw2 && my >= py && my < py + 16);
    }

    private void clickBlockSet(Setting.BlockSet bs, int y, int ix, int iw, double mx, double my) {
        java.util.List<net.minecraft.block.Block> list = bs.list();
        int cols = bsCols(iw);
        int perPage = cols * Setting.BlockSet.ROWS;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);

        // search box
        if (mx >= ix + 8 && mx < ix + iw - 8 && my >= y + 22 && my < y + 38) {
            textFocus = bs.search;
            return;
        }
        int gy = y + 44;
        int py = gy + Setting.BlockSet.ROWS * 24 + 4;
        if (my >= py && my < py + 16) {
            if (mx >= ix + 8 && mx < ix + 48) bs.page = Math.max(0, bs.page - 1);
            else if (mx >= ix + 52 && mx < ix + 92) bs.page = Math.min(pages - 1, bs.page + 1);
            else {
                int sw2 = textRenderer.getWidth("Selected only") + 14;
                if (mx >= ix + iw - 8 - sw2 && mx < ix + iw - 8) bs.selectedOnly = !bs.selectedOnly;
            }
            return;
        }
        int col = (int) Math.floor((mx - (ix + 8)) / 24.0);
        int row = (int) Math.floor((my - gy) / 24.0);
        if (col >= 0 && col < cols && row >= 0 && row < Setting.BlockSet.ROWS) {
            int idx = bs.page * perPage + row * cols + col;
            if (idx < list.size()) bs.toggle(list.get(idx));
        }
    }

    private String keyName(int key) {
        if (key < 0) return "None";
        try {
            return InputUtil.fromKeyCode(key, 0).getLocalizedText().getString();
        } catch (Exception e) {
            return "Key " + key;
        }
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        textFocus = null;
        // search box
        int sx = searchX(), sy = wy + 12;
        boolean inSearch = mx >= sx && mx < sx + 190 && my >= sy && my < sy + 20;
        searchFocus = inSearch;
        if (inSearch) return true;

        // tabs
        int tx = wx + 10, tw = TAB_W - 16;
        if (mx >= tx && mx < tx + tw && my >= top()) {
            int n = tabCount();
            for (int i = 0; i < n; i++) {
                int ty = top() + 8 + i * 30;
                if (my >= ty && my < ty + 26) {
                    catIndex = i;
                    setScroll = 0;
                    search = "";
                    listScroll = 0;
                    return true;
                }
            }
        }
        // list
        int lx = listX() + 2, lw = LIST_W - 4;
        if (mx >= lx && mx < lx + lw && my >= top() && my < bot()) {
            int y = top() + 6 - listScroll;
            int cx = lx + 6, cw = lw - 12;
            if (onThemes()) {
                for (Theme.Preset p : Theme.Preset.values()) {
                    if (my >= y && my < y + CARD_H && mx >= cx && mx < cx + cw) {
                        Theme.PRESET.set(p.ordinal());
                        setScroll = 0;
                        return true;
                    }
                    y += CARD_H + GAP;
                }
                return true;
            }
            for (Module m : visibleModules()) {
                if (my >= y && my < y + CARD_H && mx >= cx && mx < cx + cw) {
                    if (button == 1 || mx >= cx + cw - 40) m.toggle();
                    else if (mx < cx + 20) m.favorite = !m.favorite;
                    else {
                        selected = m;
                        setScroll = 0;
                        binding = false;
                    }
                    return true;
                }
                y += CARD_H + GAP;
            }
            return true;
        }
        // settings
        if ((onThemes() || selected != null) && mx >= setX() && mx < setR() && my >= top() && my < bot()) {
            int ix = setX() + 12, iw = setR() - setX() - 24;
            for (Row r : rows()) {
                if (my < r.y() || my >= r.y() + r.h()) continue;
                if (mx < ix || mx > ix + iw) break;
                switch (r.kind()) {
                    case ENABLE -> selected.toggle();
                    case BIND -> binding = !binding;
                    case FAV -> selected.favorite = !selected.favorite;
                    case SETTING -> clickSetting(r, mx, my, button, ix, iw);
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void clickSetting(Row r, double mx, double my, int button, int ix, int iw) {
        Setting<?> s = r.s();
        if (s instanceof Setting.Bool b) {
            b.value = !b.value;
        } else if (s instanceof Setting.Choice<?> c) {
            int idx = (int) Math.floor((my - (r.y() + 22)) / 15.0);
            if (idx >= 0 && idx < c.options().length) c.set(idx);
            else c.next(button == 1 ? -1 : 1);
        } else if (s instanceof Setting.Num n) {
            dragNum = n;
            dragX0 = ix + 10;
            dragW = iw - 20;
            n.setFraction((mx - dragX0) / dragW);
        } else if (s instanceof Setting.BlockSet bs) {
            clickBlockSet(bs, r.y(), ix, iw, mx, my);
        } else if (s instanceof Setting.Picks p) {
            int cols = pickCols(p, iw);
            int col = (int) Math.floor((mx - (ix + 8)) / 24.0);
            int row = (int) Math.floor((my - (r.y() + 22)) / 24.0);
            if (col >= 0 && col < cols && row >= 0) {
                int i = row * cols + col;
                if (i < p.icons.length) p.toggle(i);
            }
        } else if (s instanceof Setting.Str t) {
            textFocus = t;
        } else if (s instanceof Setting.Color c) {
            int w = (iw - 20) / 3;
            int ch = (int) ((mx - (ix + 10)) / w);
            if (ch < 0 || ch > 2) return;
            dragColor = c;
            dragCh = ch;
            dragX0 = ix + 10 + ch * w;
            dragW = w - 6;
            c.setCh(ch, (int) Math.round(255.0 * (mx - dragX0) / dragW));
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragNum != null) {
            dragNum.setFraction((mx - dragX0) / dragW);
            return true;
        }
        if (dragColor != null) {
            dragColor.setCh(dragCh, (int) Math.round(255.0 * (mx - dragX0) / dragW));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragNum = null;
        dragColor = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        layout();
        if (mx >= listX() && mx < listX() + LIST_W) {
            int max = Math.max(0, listCount() * (CARD_H + GAP) + 12 - (bot() - top()));
            listScroll = Math.max(0, Math.min(max, listScroll - (int) (vAmount * 20)));
        } else if (mx >= setX()) {
            int max = Math.max(0, contentHeight() + 12 - (bot() - top()));
            setScroll = Math.max(0, Math.min(max, setScroll - (int) (vAmount * 20)));
        }
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (textFocus != null) {
            if (chr >= 32 && chr != 127 && textFocus.value.length() < textFocus.maxLen) textFocus.value += chr;
            return true;
        }
        if (searchFocus && chr >= 32 && chr != 127 && search.length() < 24) {
            search += chr;
            listScroll = 0;
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (binding && selected != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                binding = false;
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
                selected.key = -1;
                binding = false;
            } else {
                selected.key = keyCode;
                binding = false;
            }
            return true;
        }
        if (textFocus != null) {
            Setting.Str t = textFocus;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                textFocus = null;
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!t.value.isEmpty()) t.value = t.value.substring(0, t.value.length() - 1);
            } else if (keyCode == GLFW.GLFW_KEY_V) {
                long h = MinecraftClient.getInstance().getWindow().getHandle();
                boolean ctrl = GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                        || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
                if (ctrl) {
                    String clip = GLFW.glfwGetClipboardString(h);
                    if (clip != null) {
                        String add = clip.replaceAll("[\\r\\n]+", " ");
                        String nv = t.value + add;
                        t.value = nv.length() > t.maxLen ? nv.substring(0, t.maxLen) : nv;
                    }
                }
            }
            return true;
        }
        if (searchFocus) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchFocus = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        Config.save();
        super.close();
    }
}
