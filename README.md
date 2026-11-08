# NITRO (new build, part 1)

Fabric client mod for Minecraft 1.21.4. Contains: ClickGUI, Light Detector, Base Finder (with Sus Chunk Finder).
AutoBuild is **not** in this drop yet.

## Build
Push this folder to a GitHub repo. The workflow in `.github/workflows/build.yml` builds on every push; download
the jar from the run's **Artifacts** (`nitro-jar`). If the build fails, send me the red error lines from the log.
If `fabric_version` can't be resolved, set it to the newest `+1.21.4` Fabric API version in `gradle.properties`.

## Use
Right Shift opens the ClickGUI. Rail on the left (star = favourites), modules in the middle, settings on the right.
In the settings pane: Enabled, Shortcut (click, then press a key; Backspace clears), Favourite, then module settings.
Settings are saved to `config/nitro.json` when the GUI closes and on game exit.

## Light Detector
Highlights loaded block light below Y=0 that is brighter than the sky light in the same cell. Denoise filter, colour by
light level (blue = dim, red = bright) or a custom colour, render radius 1-16 chunks, minimum light 1-15, opacity.
Scans a few chunks per tick nearest-first. Clears when disabled or when the world/dimension changes.

## Base Finder modes
Because I did not have your old Base Finder or the Amethyst Scanner source, the modes below are my own definitions:
- Classic: weighted block-clue score per chunk.
- ANBS+ Scan: clue + man-made-material score per 4x4x4 cell; chunk is flagged when enough cells pass (min-cell filter).
- Activity Clues: re-scans chunks and flags those whose clue/light signature changes between scans.
- Amethyst: Bypass: palette-only scan (does a section contain a clue block at all?), no per-block walk. Fast, coarse.
- Amethyst: Light: lit-above-sky samples below the surface, grouped into cells, min-cell filter.
- Amethyst: ANBS+ Scan: man-made blocks only, cell based.
Clue categories: containers, machinery, redstone, geodes, growth (toggle each in Filters). Natural geodes and generated
structures (mineshafts, villages, bastions) also match some clues, so expect false positives; use sensitivity and filters.

## Added in this drop
Categories: Render (Freecam, Storage ESP), HUD (Target HUD, Spotify HUD). All are toggleable in the ClickGUI, have a
Shortcut (keybind) row, and save their settings to `config/nitro.json`.

- **Freecam**: detaches the camera. WASD / Space / Shift fly, Sprint boosts, mouse looks. Your real player does not move
  or turn. Switches off on damage (setting), death, or world change. Uses two mixins (`nitro.mixins.json`).
- **Storage ESP**: outlines loaded chests, trapped chests, ender chests, barrels, shulker boxes (and optionally hoppers,
  dispensers/droppers, furnaces, brewing stands, chiseled bookshelves, decorated pots). Per-type toggles and colours, fill,
  tracers. Only block entities of chunks your client has loaded.
- **Target HUD**: name, head, health (+absorption), distance, armor and held items of the player under your crosshair, or
  the nearest player in range. Stays for a configurable hold time after you look away.
- **Spotify HUD**: current song, artist, album, artwork and a progress bar, read from the local Spotify app (no login).
  Windows: PowerShell + media session. macOS: AppleScript (allow the automation prompt). Linux: needs `playerctl`.

## Added in this drop (v2)
- **Fullbright** (Render): raises gamma past the normal limit. Original gamma is restored on disable, on leaving a world and on exit.
- **Motion Blur** (Render): frame-accumulation blur, "Blur amount" 10-90, optional frame-rate independence. Blurs the world and
  vanilla HUD; NITRO's own HUD panels are drawn after it and stay sharp. Keep it above the HUD modules in `ModuleManager`.
- **Player ESP** (Render): boxes/fill/tracers through walls, colour by custom / health / distance, optionally shows you in Freecam.
- **Storage ESP**: new "Spawners" filter and colour.
- **Spawner Alert** (World): scans loaded chunks (capped by your render distance) for mob spawners and plays a loud sound.
  Sound choice, loudness (identical sounds are layered, since one instance is capped at master volume), pitch, cooldown,
  optional repeat while in range, chat / action bar message, HUD indicator.
- **Target HUD**: redesigned. Glass / Neon / Flat styles, pop + fade animation, smooth health bar with damage trail,
  hurt flash on the head, win/lose tag, armor value, gear slots, scale and rainbow accent.
- **Spotify HUD**: redesigned. Accent colour taken from the album artwork, scrolling long titles, equalizer, progress bar with
  times, slide-in on track change, Compact mode, same three styles.
- **Song in the sky** (part of Spotify HUD): title, artist and a progress line drawn as big billboard text in the sky.
  Placement: fixed spot / slow orbit / drifts after your view. Height, distance, size, colours (artwork / rainbow / custom),
  and "hide behind terrain" are all settings.

## Added in this drop (v3)
- **Custom Sky** (Render): replaces the sky with a colour dome. Modes: Custom gradient (top / horizon / bottom colours), Solid,
  Sunset, Synthwave, Rainbow (animated) and Aurora (animated). Optional stars (twinkling, count setting), sun and moon with a
  soft glow and size setting, "Darken at night", and "Lock time of day" (only changes how the sky looks, never the real world time).
  Drawn right after the terrain with depth test and no depth write, so clouds, rain, particles and water stay on top.
  Off in the Nether / End (setting) and while under water or in lava. Note: distant terrain still fades into the vanilla fog colour.
- **Choice settings** in the ClickGUI now list every option; click the one you want (works for the Custom Sky "Sky" picker and all other choice settings).

## Added in this drop (v4)
- **Clear Weather** (Render): hides rain, snow and thunder on your screen only (and the sky darkening that comes with them).
- **Keystrokes** (HUD): WASD, space and mouse buttons with CPS. Follows your key bindings.
- **Armor HUD** (HUD): armor pieces (optionally held items) with durability bars and a durability percentage; horizontal or vertical.
- Keystrokes and Armor HUD have a corner picker (Position), X/Y offsets and scale.

## Added in this drop (v5)
- **Floating lyrics** (Spotify HUD): time-synced lyrics of the current song hang in the air under the song title (or on their own
  if "Song in the sky" is off). The current line is big, animated with the artwork / rainbow / custom colours and gently bobs and sways;
  the previous and next lines show smaller and fainter. Settings: size, gap below title, previous/next lines, float amount and a timing
  offset (+/- 3 s) if the lyrics run early or late. Lyrics are fetched in the background from the public LRCLIB service (lrclib.net,
  no account), so the game needs internet access; songs without synced lyrics there simply show a short "no synced lyrics found" note.

## Custom Sky troubleshooting
- Setting **Draw stage**: "After terrain" (default, clouds/rain stay on top) or "End of frame" (fallback; may cover clouds and particles
  in empty sky but does not depend on the mid-frame render event). If the sky never appears, switch it to End of frame.
- If the early stage draws nothing, the module falls back to the end of the frame automatically.
- The module writes what it is doing to `logs/latest.log` with the prefix `[NITRO] Custom Sky:` (drawing / skipped and why).

## Added in this drop (v6, Combat for anarchy servers)
New **Combat** category in the ClickGUI. Intended for anarchy servers where hacked clients are allowed.
- **Auto Totem**: keeps a Totem of Undying in the offhand (swap delay, optional "only replace the offhand item when low on health",
  prefer-hotbar, warning when you run out).
- **Auto Crystal**: places crystals on obsidian/bedrock and breaks them. Picks the spot with the most estimated damage to nearby
  players, within place/break range, and refuses spots that exceed "Max self damage" or would kill you. Face-place below a target
  health, place/break delays, auto-switch to crystals in the hotbar (or uses an offhand crystal). Damage is an estimate (armor and
  Resistance are modelled, enchantments are not: use "Assumed enchant reduction").
- **Anchor Macro**: look at a respawn anchor and hold right click: swaps to glowstone, charges it, swaps to the explode slot and
  detonates it. Needs an offhand totem by default. Does nothing in the Nether (anchors do not explode there).
Not compiled in my environment: if the GitHub build shows red lines, send them to me.

## v7: new ClickGUI, Info HUD removed
Info HUD is gone. The ClickGUI was rebuilt: fade/slide-in, animated violet-cyan accent, category tabs with a sliding highlight and
enabled-count badges, search box (click it and type; Esc unfocuses), module cards (click = select, right click or the switch = toggle,
star = favourite), and a settings pane with a big enable button, sliders, option chips and RGB bars. Right Shift closes.

## Combat changes
- **Auto Crystal** now has a Mode: "Hold right click" (default) places a crystal on the obsidian/bedrock you look at and breaks the
  crystal you look at while right click is held, each at its own speed (1-20 per second). "Aura (automatic)" keeps the old behaviour.
- **Anchor Macro**: set "Totem slot" to the hotbar slot your totem is in (or enable automatic detection); after charging, it switches
  to that slot to ignite the anchor. "Speed" is the delay between steps.

## Swing Animation (Render)
Changes the first-person swing: Default, Swipe, Down, Smooth, Smooth 2, Power, Feast, Twist. Strength slider, swing duration
multiplier (lower = faster; visual only, attack cooldown is unchanged) and "Also animate off hand". Inspired by the
"swinganimation" mod, implemented from scratch with two mixins (HeldItemRendererMixin, LivingEntityMixin).

## Block ESP
Outlines any blocks you pick through walls (Render tab). The "Blocks" setting shows every block that has an item as a
clickable icon grid: type in the search box (name or id), page with Prev/Next, click an icon to select it, and tick
"Selected only" to review your picks. Blocks without an icon (lava, water, fire...) can be typed into "Extra block ids".
Colour by block (automatic, one colour per block type) or one colour for all; range 1-16 chunks, max blocks drawn
(nearest first), fill/outline opacity, line width, tracers. Only loaded chunks are scanned.

## Seed Ore ESP
Predicts ore veins from the world seed (overworld, 1.21.4) and outlines them through walls, so anti-xray does not hide them.
It replays vanilla's ore generation: per-chunk population seed -> per-feature seed -> count / x,z / height -> the OreFeature
vein algorithm, using the terrain the client already has (stone vs. caves) to apply the "discard when touching air" rule.
Settings: world seed (default is the seed you gave), range, an icon grid where you click the ores you want (stone row and
deepslate row: coal, iron, gold, redstone, lapis, copper, diamond, emerald), a colour per ore, index offset, fill/outline/tracers. The HUD line shows how many *visible* (cave-exposed) ores
match the prediction: ~100% means the seed and indices are right; near 0% means wrong seed or a wrong feature index
(try Index offset -2..+2).
Limits: overworld only; predictions near player-mined or otherwise changed terrain can be off; the feature indices are
hard-coded from the vanilla feature order and not verified in-game; emerald/copper indices are the least certain.

## Themes
New **Themes** tab at the bottom of the left rail. Click a preset to apply it instantly: Nitro, Ocean, Sunset, Emerald,
Crimson, Sakura, Midnight, or Custom (two RGB accent colours). "Animated accent" turns the moving gradient on or off.
Saved in `config/nitro.json` under `theme`.
