package dev.nitro.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import net.minecraft.block.Block;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    public final String name;
    public T value;
    public final T def;
    public BooleanSupplier visible = () -> true;

    protected Setting(String name, T def) {
        this.name = name;
        this.def = def;
        this.value = def;
    }

    /** Only show this setting in the GUI while the supplier returns true. */
    public void showIf(BooleanSupplier s) {
        this.visible = s;
    }

    public abstract JsonElement save();

    public abstract void load(JsonElement e);

    public static final class Bool extends Setting<Boolean> {
        public Bool(String name, boolean def) {
            super(name, def);
        }

        public boolean on() {
            return value;
        }

        @Override
        public JsonElement save() {
            return new JsonPrimitive(value);
        }

        @Override
        public void load(JsonElement e) {
            value = e.getAsBoolean();
        }
    }

    public static final class Num extends Setting<Double> {
        public final double min, max, step;

        public Num(String name, double def, double min, double max, double step) {
            super(name, def);
            this.min = min;
            this.max = max;
            this.step = step;
        }

        public double d() {
            return value;
        }

        public float f() {
            return value.floatValue();
        }

        public int i() {
            return (int) Math.round(value);
        }

        public void set(double v) {
            v = Math.round(v / step) * step;
            value = Math.max(min, Math.min(max, v));
        }

        public void setFraction(double f) {
            set(min + (max - min) * Math.max(0, Math.min(1, f)));
        }

        public double fraction() {
            return (value - min) / (max - min);
        }

        @Override
        public JsonElement save() {
            return new JsonPrimitive(value);
        }

        @Override
        public void load(JsonElement e) {
            set(e.getAsDouble());
        }
    }

    public static final class Choice<E extends java.lang.Enum<E>> extends Setting<E> {
        private final E[] all;

        public Choice(String name, E def, Class<E> cls) {
            super(name, def);
            this.all = cls.getEnumConstants();
        }

        public E[] options() {
            return all;
        }

        public void set(int i) {
            if (i >= 0 && i < all.length) value = all[i];
        }

        public void next(int dir) {
            int i = (value.ordinal() + dir + all.length) % all.length;
            value = all[i];
        }

        @Override
        public JsonElement save() {
            return new JsonPrimitive(value.name());
        }

        @Override
        public void load(JsonElement e) {
            String s = e.getAsString();
            for (E c : all) {
                if (c.name().equals(s)) {
                    value = c;
                    return;
                }
            }
        }
    }

    /** RGB colour stored as 0xRRGGBB. */
    public static final class Color extends Setting<Integer> {
        public Color(String name, int rgb) {
            super(name, rgb);
        }

        public int rgb() {
            return value & 0xFFFFFF;
        }

        /** channel 0 = red, 1 = green, 2 = blue */
        public int ch(int c) {
            return (value >> (16 - 8 * c)) & 0xFF;
        }

        public void setCh(int c, int v) {
            int sh = 16 - 8 * c;
            v = Math.max(0, Math.min(255, v));
            value = (value & ~(0xFF << sh)) | (v << sh);
        }

        @Override
        public JsonElement save() {
            return new JsonPrimitive(value);
        }

        @Override
        public void load(JsonElement e) {
            value = e.getAsInt() & 0xFFFFFF;
        }
    }

    /** Free text value (e.g. a comma separated list of block ids). Edited in the ClickGUI. */
    public static final class Str extends Setting<String> {
        public final String hint;
        public final int maxLen;

        public Str(String name, String def, String hint, int maxLen) {
            super(name, def);
            this.hint = hint;
            this.maxLen = maxLen;
        }

        @Override
        public JsonElement save() {
            return new JsonPrimitive(value);
        }

        @Override
        public void load(JsonElement e) {
            String s = e.getAsString();
            value = s.length() > maxLen ? s.substring(0, maxLen) : s;
        }
    }

    /** A grid of block icons the user clicks to switch on or off. Saved as the list of selected labels. */
    public static final class Picks extends Setting<boolean[]> {
        public final String[] labels;
        public final Block[] icons;
        public final int perRow;

        public Picks(String name, String[] labels, Block[] icons, boolean[] def, int perRow) {
            super(name, def);
            this.value = def.clone();
            this.labels = labels;
            this.icons = icons;
            this.perRow = perRow;
        }

        public boolean on(int i) {
            return value[i];
        }

        public void toggle(int i) {
            value[i] = !value[i];
        }

        public int count() {
            int n = 0;
            for (boolean b : value) if (b) n++;
            return n;
        }

        @Override
        public JsonElement save() {
            JsonArray a = new JsonArray();
            for (int i = 0; i < labels.length; i++) if (value[i]) a.add(labels[i]);
            return a;
        }

        @Override
        public void load(JsonElement e) {
            boolean[] nv = new boolean[labels.length];
            for (JsonElement x : e.getAsJsonArray()) {
                String n = x.getAsString();
                for (int i = 0; i < labels.length; i++) if (labels[i].equals(n)) nv[i] = true;
            }
            value = nv;
        }
    }

    /**
     * Every block that has an item (so it has an icon), shown as a searchable, paged grid. The user clicks blocks
     * to select them. Saved as the list of selected block ids.
     */
    public static final class BlockSet extends Setting<Set<Block>> {
        public static final int ROWS = 5;

        public final Str search = new Str("Search", "", "search blocks...", 32);
        public int page;
        public boolean selectedOnly;

        private static List<Block> all;
        private static List<String> names;
        private String lastQuery = "", lastKey = "";
        private boolean lastSelOnly;
        private List<Block> filtered = new ArrayList<>();

        public BlockSet(String name) {
            super(name, new LinkedHashSet<>());
        }

        private static void build() {
            if (all != null) return;
            List<Block> a = new ArrayList<>();
            List<String> n = new ArrayList<>();
            for (Block b : Registries.BLOCK) {
                if (b.asItem() == Items.AIR) continue;
                a.add(b);
                n.add((b.getName().getString() + " " + Registries.BLOCK.getId(b).getPath()).toLowerCase(Locale.ROOT));
            }
            all = a;
            names = n;
        }

        public static int totalBlocks() {
            build();
            return all.size();
        }

        /** The blocks matching the search text (and the "selected only" filter), in registry order. */
        public List<Block> list() {
            build();
            String q = search.value.trim().toLowerCase(Locale.ROOT);
            String key = q + "|" + selectedOnly + "|" + value.size();
            if (!key.equals(lastKey)) {
                if (!q.equals(lastQuery) || selectedOnly != lastSelOnly) page = 0;
                lastQuery = q;
                lastSelOnly = selectedOnly;
                lastKey = key;
                List<Block> out = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) {
                    Block b = all.get(i);
                    if (selectedOnly && !value.contains(b)) continue;
                    if (!q.isEmpty() && !names.get(i).contains(q)) continue;
                    out.add(b);
                }
                filtered = out;
            }
            return filtered;
        }

        public String nameOf(Block b) {
            return b.getName().getString();
        }

        public boolean has(Block b) {
            return value.contains(b);
        }

        public void toggle(Block b) {
            if (!value.remove(b)) value.add(b);
        }

        @Override
        public JsonElement save() {
            JsonArray a = new JsonArray();
            for (Block b : value) a.add(Registries.BLOCK.getId(b).toString());
            return a;
        }

        @Override
        public void load(JsonElement e) {
            Set<Block> nv = new LinkedHashSet<>();
            for (JsonElement x : e.getAsJsonArray()) {
                String id = x.getAsString();
                for (Block b : Registries.BLOCK) {
                    if (Registries.BLOCK.getId(b).toString().equals(id)) {
                        nv.add(b);
                        break;
                    }
                }
            }
            value = nv;
        }
    }
}
