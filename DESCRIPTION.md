# World Preview

*World Preview* is a mod for visualizing Minecraft world seeds before they are generated.

This is a **community-maintained fork** of the original [World Preview](https://modrinth.com/mod/world-preview) by Caeruleus Draconis & Taiterio. The original project has not been updated since Minecraft 1.21. This fork upgrades the mod for Minecraft 1.21.11 and later, adding a wealth of new features — all while preserving the original experience.

Available for both **Fabric** and **NeoForge**.

---

## Installation

Download the latest *World Preview* JAR file for **Minecraft 1.21.11 and later**. Make sure to choose the correct file for your modloader (Fabric or NeoForge). Save the JAR to the `mods` folder of your Minecraft instance.

- **Fabric** users also need [Fabric API](https://modrinth.com/mod/fabric-api)
- **NeoForge** users just need the mod itself

---

## Usage

*World Preview* adds a new `Preview` tab to the Singleplayer menu.

![open](IMAGE_URL_PLACEHOLDER_OPEN)

Upon opening that tab, a random seed is selected and a map of biomes is generated:

![biomes](IMAGE_URL_PLACEHOLDER_BIOMES)

By default, the overworld dimension will be generated, structures will not be shown and no heightmap will be generated. This can be changed in the Settings (top-left wrench button in the `Preview` tab).

---

## Render Modes

World Preview provides multiple map rendering modes, switchable from the toolbar:

- **Biomes** — the default view, colored by biome type
- **Structures** — marks likely structure locations on the map; individual structure types can be toggled on or off

![structures](IMAGE_URL_PLACEHOLDER_STRUCTURES)

- **Heightmap** — colorizes terrain elevation, with customizable colormaps (Inferno, Viridis, etc.)

![heightmap](IMAGE_URL_PLACEHOLDER_HEIGHTMAP)

- **Y-intersections** — shows the blocks on the current Y-layer; the layer below is overlaid in a lighter shade, ideal for spotting caves and mountains

![y-int](IMAGE_URL_PLACEHOLDER_YINT)

- **Noise parameters** — visualizes the underlying noise functions: Temperature, Humidity, Continentalness, Erosion, Depth, and Weirdness. Each noise type can use its own dedicated color gradient

![noise](IMAGE_URL_PLACEHOLDER_NOISE)

---

## Zoom & Navigation

- **5 zoom levels** — from 1px/chunk (fully zoomed out) to 64px/chunk (fully zoomed in)
- **Ctrl + scroll** to zoom in/out at any time
- **Scroll wheel zoom** can be enabled in Settings (disabled by default — scroll adjusts Y-layer instead)
- **Alt + scroll** always adjusts Y, regardless of zoom mode
- **Arrow keys** to pan the map
- **Home key** to reset the preview center to origin (0, 0)

---

## Preload System

When enabled, areas beyond the visible range are preloaded in the background, significantly reducing stuttering when dragging the map. An "idle-only" option ensures preloading never competes with foreground sampling.

![preload](IMAGE_URL_PLACEHOLDER_PRELOAD)

---

## Terrain Enhancement

- **Hillshade** — overlays simulated sun illumination on the heightmap view for enhanced 3D terrain perception. Fully configurable: light azimuth, altitude, ambient intensity, and vertical exaggeration
- **Contour lines** — draws elevation contour lines on the preview map at a configurable interval. Denser lines indicate steeper terrain — builders can spot flat plateaus, survival players can find cliffs

![terrain enhancement](IMAGE_URL_PLACEHOLDER_TERRAIN)

---

## In-Game Preview

Since version 1.1.0, there is experimental support for opening the preview in-game for single-player worlds. A button is added to the pause menu for quick access without leaving the world.

![ingame](IMAGE_URL_PLACEHOLDER_INGAME)

---

## World Analysis Engine

This fork introduces a brand-new **world analysis engine** for region-level deep data analysis:

- **Region metrics** — select a region and automatically compute biome distribution, height range, mean height, slope statistics, and flat-area ratio
- **Profile sampling** — plot terrain profiles along horizontal or vertical paths to visualize elevation trends
- **Seed search** — specify a target biome and automatically search random seeds for one that contains it. Configurable via minimum area percentage and maximum search distance

![analysis](IMAGE_URL_PLACEHOLDER_ANALYSIS)

---

## Terrain Map Export

Export the current seed's terrain distribution as a high-resolution PNG image with a single click:

- Customizable coverage radius and resolution (blocks per pixel)
- Automatic terrain classification: Deep Ocean, Ocean, River, Beach, Plains, Forest, Hills, Mountain, Peak
- Optional contour line overlay
- Multi-threaded parallel export

![export](IMAGE_URL_PLACEHOLDER_EXPORT)

---

## Spawn Point Override

Place a spawn pin on the preview map, and the coordinates will be automatically applied as the world's spawn point upon creation — **no cheats required**.

![spawn](IMAGE_URL_PLACEHOLDER_SPAWN)

---

## Mod Compatibility

This fork ships with a built-in **mod compatibility framework** that auto-detects installed mods at runtime and applies appropriate adaptation strategies:

| Mod | Status |
|-----|--------|
| Terralith | Auto-adapted |
| Biomes O' Plenty | Compatible (datapack coloring) |
| Oh The Biomes You'll Go | Compatible (datapack coloring) |
| Nature's Spirit | Compatible (datapack coloring) |
| Oh The Trees | Compatible |
| Awaken | Compatible |
| Wither Storm Mod | Compatible |
| TofuCraft | Compatible |
| TerraFirmaCraft (TFC) | Known limitation |

> **TFC known limitation:** The Y-intersections view will always be a white screen for all Y levels with TFC, because the `TFCChunkGenerator` has a dummy implementation for the `getBaseColumn` method. This is not a TFC bug — TFC simply does not provide the specific information World Preview needs in this case.

---

## Other Features

- **Minimap** — a small overview map in the corner showing the current viewport position within the entire sampled area
- **Real-time statistics** — sampling progress, biome/structure counts, thread count, and elapsed time
- **Coordinate display** — current center coordinates shown at the bottom-left of the preview
- **Biome counts** — visible block count for each biome in the biome list
- **Persistent seed storage** — save frequently used seeds for quick access
- **Biome highlighting** — highlight specific biomes on the map
- **Caching system** — independent caches for in-game preview and world-creation preview, with optional data compression
- **Config backup & migration** — automatic config backups and migration from older versions
- **Highly configurable** — from thread count to sampling precision, colormaps to contour intervals

---

## Moving on the Preview

Clicking and dragging on the map moves along the X and Z axes. This triggers the following load sequence:

1. Any biomes not yet sampled on the current Y-level
2. Structures (if enabled)
3. Height map (if enabled)
4. Adjacent Y-layers (if enabled)

Scrolling moves the Y-level up and down by default (configurable to zoom instead). **Ctrl + scroll** always zooms; **Alt + scroll** always adjusts Y. Moving through Y-levels allows cave biomes to be seen. Note that non-cave biomes span the entire world height.

---

## Supported Versions

| Minecraft Version | Fabric | NeoForge |
|-------------------|--------|----------|
| `1.21.11+` | Supported | Supported |
| `26.1.2+` | Supported | Supported |
| `26.2+` | Supported | Supported |

---

## FAQ

**Q:** *Will older Minecraft versions be supported?*

**A:** No. This fork targets 1.21.11 and later only.

---

**Q:** *Will Multiplayer be supported?*

**A:** No.

---

**Q:** *Scrolling does not zoom the preview!*

**A:** By default, scrolling moves the Y-level up and down. To zoom, use **Ctrl + scroll**, or enable "Scroll wheel zooms map" in Settings. To change the visual size of a chunk, go to `Settings (top left wrench) → Resolution`.

---

**Q:** *The preview is completely white for the Y-intersections view.*

**A:** The starting Y-layer is now set to approximately sea level (Y=64) instead of the build limit. If you still see white, try scrolling down to a lower Y-layer.

---

**Q:** *My CPU is at 100%!*

**A:** You can limit the number of used cores in `Settings (top left wrench) → General`. By default, *World Preview* tries to compute the biome preview / structures / heightmap as quickly as possible. These calculations require a lot of CPU power.

---

**Q:** *How do I add support for new biomes and structures?*

**A:** New biomes, structures, and more can be registered via the Minecraft datapack mechanism. See the [World Preview dataformat docs](https://github.com/caeruleusDraconis/world-preview) for more information.

---

## Credits

- Original mod by [Caeruleus Draconis](https://github.com/caeruleusDraconis) & [Taiterio](https://github.com/Taiterio)
- Community fork maintained for Minecraft 1.21.11 and later
- Licensed under Apache-2.0
