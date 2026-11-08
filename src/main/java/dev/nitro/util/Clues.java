package dev.nitro.util;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Map;

/** Block clue table used by the Base Finder. Weights are rough "how player-made is this" scores. */
public final class Clues {
    public enum ClueType {
        CONTAINER("Containers", Items.CHEST),
        MACHINERY("Machinery", Items.PISTON),
        REDSTONE("Redstone", Items.REDSTONE),
        GEODE("Geodes", Items.AMETHYST_SHARD),
        GROWTH("Growth", Items.WHEAT);

        public final String label;
        public final Item icon;

        ClueType(String label, Item icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    public record Clue(ClueType cat, float weight, Item icon) {}

    private static final Map<Block, Clue> TABLE = new HashMap<>();
    private static final Map<Block, Boolean> ARTIFICIAL = new HashMap<>();

    private static void add(ClueType c, float w, Block b) {
        Item it = b.asItem();
        TABLE.put(b, new Clue(c, w, it == Items.AIR ? c.icon : it));
    }

    private static void add(ClueType c, float w, Block b, Item icon) {
        TABLE.put(b, new Clue(c, w, icon));
    }

    static {
        // containers
        add(ClueType.CONTAINER, 2f, Blocks.CHEST);
        add(ClueType.CONTAINER, 2f, Blocks.TRAPPED_CHEST);
        add(ClueType.CONTAINER, 2f, Blocks.BARREL);
        add(ClueType.CONTAINER, 3f, Blocks.ENDER_CHEST);
        add(ClueType.CONTAINER, 1.5f, Blocks.FURNACE);
        add(ClueType.CONTAINER, 2f, Blocks.BLAST_FURNACE);
        add(ClueType.CONTAINER, 2f, Blocks.SMOKER);
        add(ClueType.CONTAINER, 1.5f, Blocks.HOPPER);
        add(ClueType.CONTAINER, 2f, Blocks.BREWING_STAND);
        add(ClueType.CONTAINER, 1f, Blocks.CHISELED_BOOKSHELF);
        add(ClueType.CONTAINER, 1f, Blocks.LECTERN);
        for (Block b : Registries.BLOCK) {
            if (Registries.BLOCK.getId(b).getPath().endsWith("shulker_box")) add(ClueType.CONTAINER, 4f, b);
        }

        // machinery
        add(ClueType.MACHINERY, 1.5f, Blocks.PISTON);
        add(ClueType.MACHINERY, 2f, Blocks.STICKY_PISTON);
        add(ClueType.MACHINERY, 2f, Blocks.OBSERVER);
        add(ClueType.MACHINERY, 1.5f, Blocks.DISPENSER);
        add(ClueType.MACHINERY, 1.5f, Blocks.DROPPER);
        add(ClueType.MACHINERY, 2f, Blocks.TNT);
        add(ClueType.MACHINERY, 2f, Blocks.SLIME_BLOCK);
        add(ClueType.MACHINERY, 2f, Blocks.HONEY_BLOCK);
        add(ClueType.MACHINERY, 1f, Blocks.NOTE_BLOCK);
        add(ClueType.MACHINERY, 2f, Blocks.CRAFTER);
        add(ClueType.MACHINERY, 1.5f, Blocks.DAYLIGHT_DETECTOR);
        add(ClueType.MACHINERY, 4f, Blocks.BEACON);
        add(ClueType.MACHINERY, 2f, Blocks.ENCHANTING_TABLE);
        add(ClueType.MACHINERY, 1f, Blocks.JUKEBOX);

        // redstone
        add(ClueType.REDSTONE, 0.5f, Blocks.REDSTONE_WIRE, Items.REDSTONE);
        add(ClueType.REDSTONE, 1f, Blocks.REPEATER);
        add(ClueType.REDSTONE, 1f, Blocks.COMPARATOR);
        add(ClueType.REDSTONE, 1f, Blocks.REDSTONE_TORCH);
        add(ClueType.REDSTONE, 1f, Blocks.REDSTONE_WALL_TORCH, Items.REDSTONE_TORCH);
        add(ClueType.REDSTONE, 1.5f, Blocks.REDSTONE_BLOCK);
        add(ClueType.REDSTONE, 1.5f, Blocks.REDSTONE_LAMP);
        add(ClueType.REDSTONE, 1f, Blocks.LEVER);
        add(ClueType.REDSTONE, 1f, Blocks.POWERED_RAIL);
        add(ClueType.REDSTONE, 1f, Blocks.DETECTOR_RAIL);
        add(ClueType.REDSTONE, 1f, Blocks.ACTIVATOR_RAIL);
        add(ClueType.REDSTONE, 0.5f, Blocks.RAIL);
        add(ClueType.REDSTONE, 1f, Blocks.TRIPWIRE_HOOK);
        add(ClueType.REDSTONE, 1f, Blocks.TARGET);

        // geodes (natural geodes count too; switch the filter off if they are noisy)
        add(ClueType.GEODE, 1.5f, Blocks.BUDDING_AMETHYST);
        add(ClueType.GEODE, 1f, Blocks.AMETHYST_BLOCK);
        add(ClueType.GEODE, 1f, Blocks.AMETHYST_CLUSTER);
        add(ClueType.GEODE, 0.7f, Blocks.LARGE_AMETHYST_BUD);
        add(ClueType.GEODE, 0.5f, Blocks.MEDIUM_AMETHYST_BUD);
        add(ClueType.GEODE, 0.3f, Blocks.SMALL_AMETHYST_BUD);
        add(ClueType.GEODE, 2f, Blocks.TINTED_GLASS);

        // growth
        add(ClueType.GROWTH, 2f, Blocks.FARMLAND, Items.WHEAT_SEEDS);
        add(ClueType.GROWTH, 0.5f, Blocks.WHEAT, Items.WHEAT_SEEDS);
        add(ClueType.GROWTH, 0.5f, Blocks.CARROTS, Items.CARROT);
        add(ClueType.GROWTH, 0.5f, Blocks.POTATOES, Items.POTATO);
        add(ClueType.GROWTH, 0.5f, Blocks.BEETROOTS, Items.BEETROOT);
        add(ClueType.GROWTH, 1f, Blocks.NETHER_WART, Items.NETHER_WART);
        add(ClueType.GROWTH, 0.5f, Blocks.MELON_STEM, Items.MELON_SEEDS);
        add(ClueType.GROWTH, 0.5f, Blocks.PUMPKIN_STEM, Items.PUMPKIN_SEEDS);
        add(ClueType.GROWTH, 2f, Blocks.COMPOSTER);
        add(ClueType.GROWTH, 1.5f, Blocks.HAY_BLOCK);
        add(ClueType.GROWTH, 2f, Blocks.BEEHIVE);
        add(ClueType.GROWTH, 0.3f, Blocks.SUGAR_CANE, Items.SUGAR_CANE);
        add(ClueType.GROWTH, 0.3f, Blocks.CACTUS);
        add(ClueType.GROWTH, 0.5f, Blocks.COCOA, Items.COCOA_BEANS);
        add(ClueType.GROWTH, 1f, Blocks.CHORUS_FLOWER);
    }

    private static final String[] ARTIFICIAL_WORDS = {
            "planks", "bricks", "concrete", "wool", "glass", "stairs", "slab", "fence", "door", "trapdoor",
            "carpet", "ladder", "torch", "lantern", "sign", "scaffolding", "glazed", "polished", "chiseled",
            "cut_", "cobblestone", "sea_lantern", "glowstone", "iron_block", "gold_block", "diamond_block",
            "emerald_block", "netherite_block", "lapis_block", "coal_block", "bed", "banner", "bookshelf",
            "crafting_table", "button", "pressure_plate", "anvil", "cauldron", "rail", "redstone"
    };

    public static Clue get(Block b) {
        return TABLE.get(b);
    }

    public static boolean isClue(Block b) {
        return TABLE.containsKey(b);
    }

    public static ClueType category(Block b) {
        Clue c = TABLE.get(b);
        return c == null ? null : c.cat();
    }

    /** Rough "man-made material" test by registry name (cached). Structures generate some of these naturally. */
    public static boolean isArtificial(Block b) {
        Boolean v = ARTIFICIAL.get(b);
        if (v == null) {
            String p = Registries.BLOCK.getId(b).getPath();
            v = Boolean.FALSE;
            for (String w : ARTIFICIAL_WORDS) {
                if (p.contains(w)) {
                    v = Boolean.TRUE;
                    break;
                }
            }
            ARTIFICIAL.put(b, v);
        }
        return v;
    }
}
