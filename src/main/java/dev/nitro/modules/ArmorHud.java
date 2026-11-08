package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.Anchor;
import dev.nitro.util.ColorUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Armor HUD: your armor (and optionally held items) with durability bars and a durability percentage. */
public final class ArmorHud extends Module {
    public enum Layout {
        HORIZONTAL("Horizontal"), VERTICAL("Vertical");
        private final String label;
        Layout(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    private final Setting.Choice<Layout> layout = new Setting.Choice<>("Layout", Layout.HORIZONTAL, Layout.class);
    private final Setting.Choice<Anchor> anchor = new Setting.Choice<>("Position", Anchor.BOTTOM_RIGHT, Anchor.class);
    private final Setting.Bool hands = new Setting.Bool("Show held items", false);
    private final Setting.Bool percent = new Setting.Bool("Durability %", true);
    private final Setting.Num scale = new Setting.Num("Scale", 1.0, 0.6, 2.0, 0.05);
    private final Setting.Num offX = new Setting.Num("X offset", 8, 0, 400, 1);
    private final Setting.Num offY = new Setting.Num("Y offset", 56, 0, 300, 1);

    public ArmorHud() {
        super("Armor HUD", "Shows your armor with durability, optionally your held items too.", Category.HUD);
        add(layout, anchor, hands, percent, scale, offX, offY);
    }

    @Override
    public void renderHud(DrawContext ctx) {
        if (mc.player == null || mc.options.hudHidden) return;

        List<ItemStack> items = new ArrayList<>();
        EquipmentSlot[] slots = hands.on()
                ? new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND}
                : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot s : slots) {
            ItemStack st = mc.player.getEquippedStack(s);
            if (!st.isEmpty()) items.add(st);
        }
        if (items.isEmpty()) return;

        TextRenderer tr = mc.textRenderer;
        boolean horiz = layout.value == Layout.HORIZONTAL;
        boolean pct = percent.on();
        int cellW = horiz ? 22 : (pct ? 16 + 4 + tr.getWidth("100%") : 16);
        int cellH = horiz ? (pct ? 16 + 11 : 16) : 19;
        int w = horiz ? cellW * items.size() : cellW;
        int h = horiz ? cellH : cellH * items.size();

        float sc = scale.f();
        int x = anchor.value.x(ctx.getScaledWindowWidth(), (int) (w * sc), offX.i());
        int y = anchor.value.y(ctx.getScaledWindowHeight(), (int) (h * sc), offY.i());

        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(x, y, 0f);
        ms.scale(sc, sc, 1f);
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = items.get(i);
            int ix = horiz ? i * cellW + 3 : 0;
            int iy = horiz ? 0 : i * cellH;
            ctx.drawItem(st, ix, iy);
            ctx.drawStackOverlay(tr, st, ix, iy);
            if (pct && st.getMaxDamage() > 0) {
                int left = Math.max(0, 100 - st.getDamage() * 100 / st.getMaxDamage());
                String t = left + "%";
                int col = 0xFF000000 | ColorUtil.hsv(0.33f * left / 100f, 0.7f, 1f);
                if (horiz) ctx.drawText(tr, t, ix + 8 - tr.getWidth(t) / 2, iy + 18, col, true);
                else ctx.drawText(tr, t, ix + 20, iy + 4, col, true);
            }
        }
        ms.pop();
    }
}
