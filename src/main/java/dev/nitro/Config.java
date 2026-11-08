package dev.nitro;

import com.google.gson.*;
import dev.nitro.gui.Theme;
import dev.nitro.module.Module;
import dev.nitro.module.ModuleManager;
import dev.nitro.settings.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nitro.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Config() {}

    public static void load() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.has("theme")) Theme.load(root.getAsJsonObject("theme"));
            JsonObject mods = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
            for (Module m : ModuleManager.INSTANCE.modules) {
                if (!mods.has(m.name)) continue;
                JsonObject o = mods.getAsJsonObject(m.name);
                if (o.has("key")) m.key = o.get("key").getAsInt();
                if (o.has("favorite")) m.favorite = o.get("favorite").getAsBoolean();
                if (o.has("settings")) {
                    JsonObject so = o.getAsJsonObject("settings");
                    for (Setting<?> s : m.settings) {
                        if (!so.has(s.name)) continue;
                        try {
                            s.load(so.get(s.name));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (o.has("enabled") && o.get("enabled").getAsBoolean()) m.setEnabled(true);
            }
        } catch (Exception e) {
            System.err.println("[NITRO] Could not read config: " + e);
        }
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject mods = new JsonObject();
            for (Module m : ModuleManager.INSTANCE.modules) {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", m.enabled);
                o.addProperty("key", m.key);
                o.addProperty("favorite", m.favorite);
                JsonObject so = new JsonObject();
                for (Setting<?> s : m.settings) so.add(s.name, s.save());
                o.add("settings", so);
                mods.add(m.name, o);
            }
            root.add("modules", mods);
            root.add("theme", Theme.save());
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            System.err.println("[NITRO] Could not save config: " + e);
        }
    }
}
