# World Preview — Community Fork

*World Preview* draws a map of a Minecraft world seed before the world is generated: biomes,
structures, heightmap and Y-layer intersections.

> **This is an unofficial community fork** of
> [World Preview](https://modrinth.com/mod/world-preview) by Caeruleus Draconis & Taiterio.
> The original stopped at Minecraft 1.21. This fork adds 1.21.11, 26.1.2 and 26.2, on Fabric and
> NeoForge, plus a number of extra features.
> All credit for the mod itself belongs to its authors. Licensed under Apache-2.0.

**Download** → [CurseForge](https://www.curseforge.com/minecraft/mc-mods/world-preview-unofficial-port) · [GitHub Releases](../../releases) · **Report a bug** → [Issues](../../issues)

---

## Supported versions

| Minecraft | Fabric | NeoForge | Branch |
|-----------|--------|----------|--------|
| `1.21.11` | ✅ | ✅ | `1.21.11-fabric` / `1.21.11-neoforge` |
| `26.1.2`  | ✅ | ✅ | `26.1.2-fabric` / `26.1.2-neoforge` |
| `26.2`    | ✅ | ✅ | `26.2-fabric` / `26.2-neoforge` |

Minecraft versions older than 1.21.11 are not supported.

> The original project's pages ([Modrinth](https://modrinth.com/mod/world-preview),
> [CurseForge](https://www.curseforge.com/minecraft/mc-mods/world-preview)) do not include this
> fork's builds. This fork ships on
> [CurseForge](https://www.curseforge.com/minecraft/mc-mods/world-preview-unofficial-port) and
> through [Releases](../../releases).

## Installation

1. Download the jar for your **exact** Minecraft version **and** mod loader, from
   [CurseForge](https://www.curseforge.com/minecraft/mc-mods/world-preview-unofficial-port) or
   [Releases](../../releases).
   Asset names look like `world_preview-<mod version>-<minecraft version>-<Fabric|NeoForge>.jar`;
   the `.dev` and `.sources` variants are for developers and are not meant to be played.
2. Put the jar into the `mods` folder of your Minecraft instance.
3. On Fabric you also need [Fabric API](https://modrinth.com/mod/fabric-api). NeoForge needs nothing extra.

---

## What differs from the original mod

The list below records where this fork departs from the original. Anything not listed here is
unchanged from upstream behaviour.

### Changed behaviour

| | Original (≤ MC 1.21) | This fork |
|---|---|---|
| **Scroll wheel** | moves along the Y axis | zooms the map; `Ctrl`+scroll always zooms and `Alt`+scroll always moves along Y, and the bare wheel can be switched back in `Settings → General → Scroll wheel zooms map` |
| **Zoom** | set from the config menu | a 5-step ladder (16, 8, 4, 2 or 1 pixels per chunk; 4 by default), reachable from the mouse wheel, the settings screen and a scale-bar slider at the bottom-left of the map. The two widest levels resample the map, so they take a moment |
| **Minecraft version** | 1.20.x, 1.21.x | 1.21.11, 26.1.2, 26.2 |
| **Mod loader** | Fabric, Forge | Fabric, NeoForge |
| **Settings screen** | a single screen | sidebar with separate pages, plus a *Reset to defaults* button |
| **Starting Y layer** | the build limit | about one third of the world height (≈ Y 64 in the Overworld) |

### Added in this fork

Most of the following are opt-in: they are reached through their own buttons or settings and do
not change the preview until you use them.

**Seed search** scans random seeds for a combination of criteria — up to four biomes (any-of), a
structure, a minimum biome area share, and a maximum distance from the anchor point. Results are
ranked and run on the worker pool, so a search keeps going while you are on another screen.
Structure criteria cover random-spread structures; concentric-ring ones such as strongholds are
not supported yet.

The seed screen is a **hub**: the seed box with Random and Save, the search criteria, and the
results, history, favourites and saved seeds as tabs of one list. Searches that hit are stored,
and a row can be clicked to re-apply a seed or favourited. A **seed comparison** screen puts the
current seed next to up to seven others and reports biome diversity, water share, the dominant
biome and a spawn score.

The **world analysis engine** works on a region you select: biome distribution, height range,
mean height, slope statistics and flat-area ratio, plus a terrain cross-section, a height
histogram and a spawn quality score with reasons. The tables export as CSV and JSON into
`config/world_preview/reports/`.

**Terrain map export** writes the current seed's terrain as a high-resolution PNG, with a
configurable radius and blocks-per-pixel, nine terrain classes and an optional contour overlay.
It can also walk every dimension of the seed in one batch. Output goes to
`config/world_preview/terrain_exports/`. There is also a plain "save the preview as PNG" button
in the settings.

**Waypoints** are named, coloured pins stored per seed and dimension: left-click the map to place
one, left-click an existing pin to rename, recolour or delete it. The **measure tool** works with
two clicks and reports the distance and the axis deltas; right-click clears it. **Spawn point
override** puts a spawn pin on the map and applies those coordinates when the world is created,
without cheats.

For the heightmap view there is **hillshade** (simulated sun illumination, with azimuth,
altitude, ambient and exaggeration) and **contour lines** at a configurable interval. The
**noise parameter views** cover temperature, humidity, continentalness, erosion, depth, weirdness
and peaks & valleys, each with its own colour gradient; noise maps are drawn as smooth gradients
that line up with biome boundaries.

Smaller things: a **minimap** and live statistics, coordinates, per-biome block counts,
**preloading** of the area around the viewport (optionally only when worker threads are idle), and
a **mod compatibility framework** that adapts to modded chunk generators — currently Terralith,
Biomes O' Plenty, TerraFirmaCraft, Oh The Biomes You'll Go, AstralsDimension, Nature's Spirit, Oh
The Trees, Awaken, Witherstorm and TofuCraft. Mods that only add biomes or structures need no
special handling at all. Sampling, export and analysis also cover **other dimensions**, including
ones with non-standard height limits.

**Performance** has had several passes over the render path, sampling and idle-frame cost, mostly
aimed at dragging and at worlds loaded from cache. Ongoing rather than finished.

### Taken over from the original unchanged

- The biome, heightmap and Y-intersection views, and the structure visibility toggles.
- The load order while dragging: biomes → structures → heightmap → intersections → adjacent
  Y layers.
- Click-and-drag panning, arrow-key panning, `Home` to recentre on the origin.
- Persistent seed storage, biome highlighting, the cache for in-game and world-creation
  previews (with optional compression), config backup and migration, the thread-count setting.
- The in-game preview from the pause menu (singleplayer only).
- The datapack mechanism for registering new biomes, structures and colour maps.

---

## Usage

> The screenshots in this section are placeholders — they are being redone for the current UI.

*World Preview* adds a `Preview` tab to the Singleplayer menu.

> **Screenshot pending** — `img/preview-tab.png`: the Preview tab with its sidebar
> (Biomes / Structures / Seeds), the map, and the scale bar at the bottom-left.

Opening it samples a random seed and draws a biome map. By default the Overworld is previewed,
structures and the heightmap are off, and no noise samples are stored — all of that is
configured in `Settings` (the wrench button in the top-left).

### Moving on the map

- **Drag** the map to travel along X and Z. This queues, in order: biomes not yet sampled on
  the current Y layer → structures (if enabled) → heightmap (if enabled) → intersections
  (if enabled) → adjacent Y layers (if enabled).
- **Scroll** to zoom by default. `Ctrl`+scroll always zooms, `Alt`+scroll always moves along
  the Y axis; the behaviour of the bare wheel is a setting.
- Moving along Y lets you see cave biomes. Note that non-cave biomes span the whole world
  height, so they will look the same on every layer.
- **Arrow keys** pan, **Home** recentres on (0, 0).
- The **scale bar** at the bottom-left shows the current scale and doubles as a zoom slider.

### Render modes

Switch from the toolbar in the preview:

| Mode | Shows |
|---|---|
| **Biomes** | biome colours (default) |
| **Heightmap** | colourised elevation, with selectable colour maps |
| **Y-intersections** | blocks on the current Y layer, with the layer below drawn in a lighter shade |
| **Noise parameters** | temperature, humidity, continentalness, erosion, depth, weirdness, peaks & valleys |

Structures are not a view of their own: once structure sampling is on, individual structure types
are toggled from the structures list.

> **Screenshots pending**, one per mode: `img/render-biomes.png`, `img/render-structures.png`,
> `img/render-heightmap.png`, `img/render-y-int.png`, `img/render-noise.png`.

### Seed tools

The `Seeds` button in the sidebar opens the seed hub: the seed box with Random and Save, the
search criteria, and the results / history / favourites / saved-seeds tabs.

> **Screenshot pending** — `img/seeds-hub.png`: the criteria panel on the left and the tabbed
> results panel on the right.

### World analysis

The `Analysis` button opens the analysis screen: region coordinates define the analysed area,
with a stats panel, a terrain cross-section, a height histogram and biome/terrain share tabs.

> **Screenshot pending** — `img/analysis.png`.

### In-game preview

For singleplayer worlds a button is added to the pause menu, so you can open the preview
without leaving the world.

> **Screenshot pending** — `img/ingame.png`: the preview opened from the pause menu.

---

## FAQ

**Q: Scrolling does not zoom the preview!**

**A:** Scrolling zooms by default in this fork. Check
`Settings → General → Scroll wheel zooms map`; when that box is off, the wheel moves along the
Y axis instead and you need `Ctrl`+scroll to zoom. `Alt`+scroll always moves along Y.
A blank Y-intersections view is a different problem; see the next entry.

---

**Q: The Y-intersections view is completely white / black.**

**A:** The preview starts at roughly one third of the world height (≈ Y 64 in the Overworld).
If you still see nothing, scroll to a lower Y layer — or to a higher one, in a dimension whose
terrain sits above its middle.

---

**Q: My CPU is at 100%!**

**A:** Lower the thread count in `Settings → General` (the setting is labelled *Number of biome
sampling threads*). *World Preview* computes the biome preview, structures and heightmap as fast
as it can, which is CPU-hungry by design. Limiting preloading to idle worker threads, and
lowering the sampling precision, keep the load down without changing what is drawn.

---

**Q: Will older Minecraft versions be supported?**

**A:** No. This fork targets 1.21.11 and later. For 1.20/1.21 use the original mod.

---

**Q: Does this run on a server, or in multiplayer?**

**A:** It is a client-side mod and singleplayer-only. Install it on the client; a server does not
need it, and the preview is not available on a server world.

---

**Q: Where is the config file, and what else does the mod write to disk?**

**A:** `config/world_preview/config.json`. Backups and migrations of older files happen
automatically. Terrain exports land in `config/world_preview/terrain_exports/` and analysis
reports in `config/world_preview/reports/`.

---

**Q: How do I add support for new biomes, structures or colour maps?**

**A:** The same way as in the original mod: through the Minecraft datapack mechanism. The
data format is unchanged by this fork.

---

**Q: Fabric or NeoForge? Do I need anything else?**

**A:** Pick the jar that matches both your Minecraft version and your loader. Fabric users
also need Fabric API; NeoForge users do not need anything beyond NeoForge itself.

---

## Mod compatibility and known issues

This mod is compatible with most mods, including those that add biomes and dimensions.
For a list of mods with dedicated compatibility handling, see *Added in this fork* above.

### TerraFirmaCraft (TFC)

World Preview **is** compatible with TFC, with one known limitation: the Y-intersections view
stays white on every Y level, because `TFCChunkGenerator` has a dummy implementation of
[`getBaseColumn`](https://github.com/TerraFirmaCraft/TerraFirmaCraft/blob/v3.1.2-beta/src/main/java/net/dries007/tfc/world/TFCChunkGenerator.java#L643-L646).

This is a limitation of the data TFC exposes, not a TFC bug.

---

## Credits and license

- The original mod, its design and the large majority of its code are by
  [Caeruleus Draconis](https://github.com/caeruleusDraconis) & [Taiterio](https://github.com/Taiterio).
  This fork only exists because of their work.
- Anything the port adds, and anything it breaks, is this fork's own responsibility.
- Licensed under **Apache-2.0**; see [LICENSE](LICENSE).
- User-visible release history: [CHANGELOG.md](CHANGELOG.md).
- A per-file record of the upstream sources this fork modified ships as `CHANGES.md` in each
  version branch — for example
  [1.21.11-fabric](https://github.com/seisou123/World-Preview-Unofficial-Port-/blob/1.21.11-fabric/CHANGES.md).
