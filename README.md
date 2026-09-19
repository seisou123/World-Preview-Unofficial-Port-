# World Preview — Community Fork

*World Preview* renders a map of a Minecraft world seed — biomes, structures, heightmap and
Y-layer intersections — before the world is ever generated.

> **This is an unofficial community fork** of
> [World Preview](https://modrinth.com/mod/world-preview) by Caeruleus Draconis & Taiterio.
> The original was last updated for Minecraft 1.21; this fork carries it forward to Minecraft
> 1.21.11, 26.1.2 and 26.2, on Fabric and NeoForge, with a number of additional features.
> All credit for the mod itself belongs to its authors. Licensed under Apache-2.0.

**Download** → [Releases](../../releases) · **Report a bug** → [Issues](../../issues)

---

## Supported versions

| Minecraft | Fabric | NeoForge | Branch |
|-----------|--------|----------|--------|
| `1.21.11` | ✅ | ✅ | `1.21.11-fabric` / `1.21.11-neoforge` |
| `26.1.2`  | ✅ | ✅ | `26.1.2-fabric` / `26.1.2-neoforge` |
| `26.2`    | ✅ | ✅ | `26.2-fabric` / `26.2-neoforge` |

Minecraft versions older than 1.21.11 are not supported by this fork; for 1.20 / 1.21 please
use the original mod.

> The Modrinth and CurseForge pages belong to the original project and do not include this
> fork's builds. Please download from [Releases](../../releases) in this repository.

## Installation

1. Download the jar for your **exact** Minecraft version **and** mod loader from [Releases](../../releases).
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
| **Zoom** | set from the config menu | a 5-step ladder (16 / 8 / 4 / 2 / 1 pixels per chunk), reachable from the mouse wheel, the settings screen and a scale-bar slider at the bottom-left of the map |
| **Minecraft version** | 1.20.x, 1.21.x | 1.21.11, 26.1.2, 26.2 |
| **Mod loader** | Fabric, Forge | Fabric, NeoForge |
| **Settings screen** | a single screen | sidebar with separate pages, plus a *Reset to defaults* button |
| **Starting Y layer** | the build limit | about one third of the world height (≈ Y 64 in the Overworld) |

### Added in this fork

Most of the following are opt-in: they are reached through their own buttons or settings and do
not change the preview until you use them.

- **Seed search** — search random seeds for a combination of criteria: up to four biomes
  (any-of), a structure, a minimum biome area share and a maximum distance from the anchor
  point. Results are ranked, run on the worker pool in the background, and keep running when
  you leave the screen.
- **Seed history, favourites and comparison** — every search is recorded; click a row to
  re-apply a seed, favourite it, or compare the current seed against up to three saved seeds
  by biome diversity, water share, dominant biome and spawn score.
- **Seeds hub** — the seed box, Random/Save buttons, results, history, favourites and saved
  seeds live in one screen that is opened from the sidebar.
- **World analysis engine** — pick a region on the map and get biome distribution, height
  range, mean height, slope statistics, flat-area ratio, a terrain cross-section, a height
  histogram and a **spawn quality score** with reasons. Reports export as CSV + JSON into
  `config/world_preview/reports/`.
- **Terrain map export** — export the current seed's terrain as a high-resolution PNG, with a
  configurable radius/resolution, terrain classification, optional contour overlay, and a
  batch mode that exports every dimension of the seed at once. (There is also a plain
  "save the preview as PNG" button in the settings.)
- **Waypoints** — name and colour pins per seed and dimension; left-click the map to place,
  right-click a pin to remove. Pins persist.
- **Measure tool** — two clicks measure the distance and the axis deltas between two points;
  right-click clears it.
- **Spawn point override** — place a spawn pin on the preview and those coordinates are
  applied as the world's spawn point when the world is created, without cheats.
- **Hillshade and contour lines** — simulated sun illumination on the heightmap view
  (azimuth, altitude, ambient, exaggeration) and elevation contour lines at a configurable
  interval.
- **Noise parameter views** — dedicated views for temperature, humidity, continentalness,
  erosion, depth and weirdness, each with its own colour gradient. Noise maps are drawn as
  smooth gradients aligned with real biome boundaries.
- **Minimap, statistics, coordinates and biome counts** — a small overview map of the sampled
  area, live sampling progress and thread counts, the centre coordinates, and the visible
  block count per biome.
- **Preloading** — areas beyond the visible range are sampled ahead of time so that dragging
  the map stutters less; can be restricted to idle worker threads and given a radius.
- **Mod compatibility framework** — modded chunk generators are detected at runtime and
  adapted. Currently registered: Terralith, Biomes O' Plenty, TerraFirmaCraft, Oh The Biomes
  You'll Go, AstralsDimension, Nature's Spirit, Oh The Trees, Awaken, Witherstorm, TofuCraft.
  Other mods that only add biomes or structures keep working through the datapack mechanism.
- **Multi-dimension support** for sampling, export and analysis, including dimensions with
  non-standard height limits.
- **Performance work** — a series of passes over the render path, sampling and idle-frame cost,
  mainly aimed at map dragging and at worlds loaded from the cache. Ongoing rather than finished.

### Taken over from the original unchanged

- The biome, structure, heightmap and Y-intersection views, and the load order while dragging
  (biomes → structures → heightmap → adjacent Y layers).
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
  the current Y layer → structures (if enabled) → heightmap (if enabled) → adjacent Y layers
  (if enabled).
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
| **Structures** | likely structure starts; individual types can be toggled |
| **Heightmap** | colourised elevation, with selectable colour maps |
| **Y-intersections** | blocks on the current Y layer, with the layer below drawn in a lighter shade |
| **Noise parameters** | temperature, humidity, continentalness, erosion, depth, weirdness |

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
The Y-intersections view showing nothing is a separate issue, see below.

---

**Q: The Y-intersections view is completely white / black.**

**A:** The preview starts at roughly one third of the world height (≈ Y 64 in the Overworld).
If you still see nothing, scroll to a lower Y layer — or to a higher one, in a dimension whose
terrain sits above its middle.

---

**Q: My CPU is at 100%!**

**A:** Limit the number of used cores in `Settings → General → Threads`. By default *World
Preview* tries to compute the biome preview, structures and heightmap as quickly as possible,
which is CPU-hungry by design. Enabling preloading and caching, and lowering the sampling
precision, also help.

---

**Q: Will older Minecraft versions be supported?**

**A:** No. This fork targets 1.21.11 and later. For 1.20/1.21 use the original mod.

---

**Q: Will multiplayer be supported?**

**A:** No.

---

**Q: How do I add support for new biomes, structures or colour maps?**

**A:** The same way as in the original mod: through the Minecraft datapack mechanism. The
data format is unchanged by this fork.

---

**Q: Fabric or NeoForge? Do I need anything else?**

**A:** Pick the jar that matches both your Minecraft version and your loader. Fabric users
also need Fabric API; NeoForge users do not need anything beyond NeoForge itself.

---

## Mod incompatibilities

This mod is compatible with most mods, including those that add biomes and dimensions.
For a list of mods with dedicated compatibility handling, see *Added in this fork* above.

### TerraFirmaCraft (TFC)

World Preview **is** compatible with TFC, with one known limitation: the Y-intersections view
stays white on every Y level, because `TFCChunkGenerator` has a dummy implementation of
[`getBaseColumn`](https://github.com/TerraFirmaCraft/TerraFirmaCraft/blob/v3.1.2-beta/src/main/java/net/dries007/tfc/world/TFCChunkGenerator.java#L643-L646).

TFC is not broken — it simply does not expose the information World Preview needs for that
view.

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
