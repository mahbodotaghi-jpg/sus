package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import dev.nitro.util.CombatUtil;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Auto Totem: keeps a Totem of Undying in your offhand. When the totem pops, the next one is swapped in after the
 * configured delay.
 */
public final class AutoTotem extends Module {
    private final Setting.Num delay = new Setting.Num("Delay (ticks)", 0, 0, 10, 1);
    private final Setting.Bool replace = new Setting.Bool("Replace offhand item", true);
    private final Setting.Num danger = new Setting.Num("Replace only below health", 12, 1, 36, 1);
    private final Setting.Bool hotbarFirst = new Setting.Bool("Prefer hotbar totems", false);
    private final Setting.Bool chat = new Setting.Bool("Tell me when out of totems", true);

    private int cooldown;
    private boolean warned;

    public AutoTotem() {
        super("Auto Totem", "Keeps a Totem of Undying in your offhand.", Category.COMBAT);
        add(delay, replace, danger, hotbarFirst, chat);
        danger.showIf(() -> !replace.on());
    }

    @Override
    public void onEnable() {
        cooldown = 0;
        warned = false;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) return;

        ItemStack off = mc.player.getOffHandStack();
        if (off.isOf(Items.TOTEM_OF_UNDYING)) {
            warned = false;
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (!off.isEmpty() && !replace.on() && CombatUtil.effectiveHealth(mc.player) > danger.d()) return;

        int inv = findTotem();
        if (inv < 0) {
            if (chat.on() && !warned) {
                mc.player.sendMessage(net.minecraft.text.Text.literal("[NITRO] Auto Totem: no totems left")
                        .formatted(net.minecraft.util.Formatting.RED), false);
                warned = true;
            }
            return;
        }
        int screenSlot = inv < 9 ? 36 + inv : inv;
        // button 40 = swap the clicked slot with the offhand
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screenSlot, 40, SlotActionType.SWAP, mc.player);
        cooldown = delay.i();
    }

    private int findTotem() {
        Item t = Items.TOTEM_OF_UNDYING;
        if (hotbarFirst.on()) {
            for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(t)) return i;
            for (int i = 9; i < 36; i++) if (mc.player.getInventory().getStack(i).isOf(t)) return i;
        } else {
            for (int i = 9; i < 36; i++) if (mc.player.getInventory().getStack(i).isOf(t)) return i;
            for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(t)) return i;
        }
        return -1;
    }
}
