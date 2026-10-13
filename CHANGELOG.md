## 1.5.5

### Fixes

- Fixed the exported terrain map PNG showing red and blue swapped on Minecraft 1.21.11 and the 26.x versions, and the same swap on the export screen's legend swatches
- Fixed the terrain map export failing with out-of-memory on extreme coverage radius and resolution combinations: such combinations are now rejected up front with an explanation on the export screen
- Fixed the profile direction button on the analysis screen not switching on Minecraft 1.21.11 and the 26.x versions
- Fixed mouse buttons acting as the wrong button on Minecraft 26.3: right-click actions such as copy coordinates and list context menus fired on left click, while left-click actions such as list selection, measure points, spawn pins and waypoints did not respond
- Fixed the spawn point override: the pinned spawn only applies to the world being created, opening an existing world first no longer touches its spawn, and a failed apply is logged instead of silently counting as applied
- Fixed resources leaking when the preview screen was closed while a settings reload was still running

### Improvements

- While the map is dragged, the biome layer now samples one sample per chunk and the detail layers (structures, heightmap, intersections and the adjacent Y layers) wait for the drag to end; the coarsely filled area is re-sampled at full resolution in place on release. The behaviour is on by default and has no setting of its own yet
- Reworked the terrain map export screen layout: settings, status and the category legend now sit in outlined panels matching the seed search and analysis screens, the export/settings tab pair is gone, the layout no longer overflows small gui heights such as GUI scale 4, and the progress bar keeps showing its percentage and coloring failures red
- A refused export start now reports the reason instead of doing nothing
- A batch export no longer aborts the remaining dimensions when one dimension fails, and batch runs now show elapsed and remaining time like single-dimension exports
- Reworked the analysis screen's biome share table: holding the middle mouse button autoscrolls it at a speed set by the cursor's distance from the press point (it keeps scrolling while the mouse holds still), the wheel scrolls three rows per step with a short glide, and a scrollbar on the right edge tracks the visible range, jumps under the cursor on press and drags 1:1; on layouts too small for a single table row the stacked-bar header collapses to the title, diversity line and biome bar. The same middle-press autoscroll and short wheel glide now cover the analysis overview panel, the settings pages, the biome and structure lists, the seed search lists and the terrain export history, whose scrollbar strips also jump under the cursor and drag 1:1; the export history keeps every entry instead of the twelve most recent
- The analysis screen's terrain export and structure waypoint actions joined the report and back buttons in one footer row beneath the panels
- Terrain map exports can overlay a coordinate grid (16 to 4096 blocks, opt-in per export) and write an optional grayscale heightmap PNG next to the terrain PNG; the export metadata JSON now also carries per-category pixel counts
- The analysis screen gained an Export This Region action that opens the terrain export screen prefilled with the analyzed area
- The in-game preview now remembers the last map position between sessions; an opt-in Open preview at player setting anchors the initial view on the player instead
- The hover panel can show the sampled temperature, humidity, continentalness, erosion, depth, weirdness and peaks-valleys values behind an opt-in setting, measured distances show the surface height difference between both endpoints, and viewport PNG exports write a JSON sidecar with the seed, dimension, world identity and view center
- The analysis screen's structure rows are clickable to add a waypoint, and a footer button drops every structure in the analyzed region into the waypoint list at once
- Terrain map exports append to a history file and the export screen browses the full history; clicking a row copies the exported file's absolute path
- Holding Ctrl while right-clicking a seed search result saves it to the saved-seed library, and the history view gained a button that clears all non-favorite entries
- Right-clicking a waypoint pin on the preview map copies a /tp command with the waypoint's coordinates
- Terrain map exports can color the map per biome (opt-in): every pixel takes the biome's preview color — the same colors the preview's biome view uses, including user overrides — instead of the nine terrain categories, and the export metadata JSON carries a per-biome legend mapping every used color to its biome id and name
- Terrain map exports sample biomes at the Y layer the preview is scrolled to instead of a fixed Y 64, so the exported map matches the layer being viewed and the storage fast path applies to it; every dimension of a batch export samples at that same layer and the metadata JSON records it
- The Russian and Portuguese (Portugal) translations now cover every language key, with a language parity test guarding key coverage, key order and format placeholders; two pre-existing placeholder mistakes that could crash tooltip rendering in those locales were fixed

### Internal

- Added a shared pixel allocation limit for terrain exports, and tests covering the per-version pixel formats
- Worker and icon-loading exceptions are now written to the game log instead of bare stack traces

## 1.5.4

Minecraft 26.3 and Minecraft 1.20.1 are now supported: 26.3 on Fabric and NeoForge, 1.20.1 on Fabric and Forge.

### Fixes

- Fixed the settings screen Reset buttons doing nothing or snapping to the wrong value: each page's Reset now restores that page's defaults
- Fixed the exported terrain height field giving peaks the lowest value instead of the highest
- Fixed a single malformed entry discarding a whole resource file: biome colour maps, structure maps and colormaps now skip the offending entry and keep the rest
- Fixed mod compatibility on Fabric: installed mods are enumerated again, so the modded chunk generator adapters are selected again
- Fixed contour lines breaking up on sloped terrain and the hue bar drawing a black-to-white gradient instead of the hue spectrum
- Fixed a shared icon image being freed twice, the render-complete marker being recorded before the preview texture was generated, and two cross-thread state paths reading stale or torn values
- Fixed the heightmap scan missing the topmost partial noise cell when the visual Y-range limit was on, and the preview disk cache not distinguishing height sampling settings: existing caches recompute once after this update
- Fixed the preview keeping the previous zoom scale after changing the resolution on the settings screen
- Fixed the floating biome and structure panels covering the rail buttons and the scale bar so that covered text no longer shows through, and the Waypoints and Measure buttons scrolling their labels inside the button
- Fixed empty-state hints in the analysis panels overflowing narrow panels: over-wide hints now wrap or are clipped to the panel

### Improvements

- The analysis area is now enabled by default, matching the seed search button
- The analysis screen's right column now has a Map tab beside the Profile, Heights and Biomes tabs, and all four pages share one area that ends at the same bottom edge
- The profile direction button moved into the profile page's title row; box-selecting on the map switches to the map page, and starting an analysis switches to the profile page
- The scale bar on narrow maps is more compact
- An Expand control opens the current analysis page as a near-fullscreen overlay; Collapse, Esc or the inventory key return to the panel layout, and the tab row stays usable inside the overlay
- The floating panels open directly below their toggle button and size their width to the longest list entry

### Internal

- Added tests for the settings Reset button, the terrain classifier, the heightmap scan bounds, the preview cache key, the contour landing points and render settings round-trips
- Added tests for the floating panel width policy and the overlay tab layout

## 1.5.3

### Fixes

- Fixed the analysis screen layout on small windows: the profile chart and chart tabs no longer overlap the footer buttons, and the overview panel reaches the bottom of the screen
- Fixed a phantom selection band at the right edge of the region coordinate fields
- Fixed the map border overlapping the seed row buttons
- Fixed the spawn score ignoring nearby structures, mis-scaled slope scoring and a distorted water share when the preview layer is underground

### Improvements

- Noise maps (temperature, humidity, continentalness, erosion) now draw smooth gradients instead of color bands, aligned with real biome boundaries
- The "Analysis" button moved to the sidebar, below the Seeds button
- The world analysis screen was rebuilt in the seed search style: region coordinates now define the analyzed area, a stats panel with spawn score, a terrain cross-section, a height histogram, biome/terrain share tabs, map box-selection, structure distances, and CSV/JSON reports
- The mouse wheel and the settings screen share one zoom ladder (16 down to 1 pixels per chunk); zooming applies instantly instead of rebuilding the map, and hitting a limit shows a hint
- A scale bar with a zoom slider sits in the bottom-left of the map: click or drag it to change the zoom level

## 1.5.2

### Improvements

- Opening the settings screen or switching preview tabs no longer discards sampled map data - returning is now instant instead of a full rebuild, and changing only the seed no longer reloads game resources
- Preview maps render noticeably faster, especially worlds loaded from the cache and in terrain-height mode; idle frames now do almost no work
- Sampling is faster, seed searches with multiple criteria finish sooner, and multi-dimension terrain export is much quicker
- Waypoint and hover-tooltip rendering cost per frame reduced
- The seed search screen has been redesigned: a grouped criteria panel with full-height biome color bars on the left, a tabbed results panel with empty-state hints on the right, and a new More Search Options sub-screen for the advanced parameters

### Fixes

- Fixed seed history and favorites rows drawing the seed twice with overlapping text for entries recorded without a search label
- Fixed list rows rendering displaced and clipped until the first scroll (affected the biome picker, search results and other shared lists)
- Seed comparison no longer marks text (non-numeric) seeds as unavailable
- Fixed the structure picker showing no icons for texture-based structures (only the two item-based ones appeared)

## 1.5.1

### New Features

- The seed search screen is now a unified "Seeds" hub: the seed box with Random and Save buttons lives inside the screen, and results, history, favorites and saved seeds are tabs of one shared list; the old sidebar Seeds list was removed

### Improvements

- Preview maps load and draw noticeably faster: dragging feels more responsive, and the minimap, per-pixel noise effects and the analysis screen are much lighter
- The saved-seeds workflow works again: the current seed is marked, clicking a saved seed applies it, and saving takes effect immediately
- Seed search runs on several worker threads for faster hits; typing a new seed re-samples after a short pause instead of on every keystroke

### Fixes

- Fixed the y-intersections view staying black when its data had not been sampled yet
- Fixed the waypoint naming dialog opening empty and crashing
- Fixed multi-dimension terrain export flattening deep and tall dimensions
- Seed searches are more robust: restarting no longer gets blocked by a just-stopped search, a crashed search no longer hangs, and searches no longer time out after 30 seconds

## 1.5.0

### New Features

- Added seed search with multiple criteria: combine a biome criterion (up to 4 biomes as an any-of group, chosen in a filterable picker) with a structure criterion (villages, bastions, ancient cities, ...); all criteria must pass for a seed to hit
- Search results are ranked (best first) and limited by the configurable number of hits; the search anchor can be the map center or the world origin
- Added search history & favorites: persisted to config, click a row to re-apply a seed, shift+right-click to favorite it, right-click to delete it
- Right-click a biome in the biome list to open the seed search screen pre-filled with that biome; the search starts automatically
- Structures are picked in a dedicated structure selection screen with a filter box, item icons and a "None" row
- Added map waypoints: named, colored pins persisted per seed/dimension; left-click the map to place one via a naming dialog, right-click a pin to remove it
- Added a measure tool: two clicks on the map measure distance and axis deltas, right-click clears the measurement
- Double-click a structure in the structures list to center the map on its nearest instance
- Added analysis report export: the analysis screen can export the biome share table as CSV plus a JSON summary into config/world_preview/reports/
- Added multi-dimension terrain export: batch-export terrain maps for every dimension of the current seed with per-dimension progress and cancel support, optionally with a block-coordinate grid overlay
- Added spawn analysis: the analysis panel shows a spawn quality score (0-100) with reasons and lists the region's top 5 biomes with their share and rarity stars
- Added a seed comparison screen: compares the current seed with up to seven saved seeds by biome diversity, water share, most common biome and spawn score
- Structure criteria support random-spread structures; concentric-ring structures such as strongholds are not supported yet

### Improvements

- Searches now run in the background: starting a search returns to the preview map, reopening the seed search screen re-attaches to the running search or shows the last result, and leaving the screen no longer cancels it (Stop cancels explicitly)
- The default number of search results was lowered from 5 to 1
- Compacted the seed search screen: criteria controls are laid out three per row across two rows, the biome picker and results list sit side by side, and the layout adapts to the screen height so nothing overlaps or falls off-screen
- Added a back button to the seed search screen and to the seed comparison screen
- The seed search button now uses the sidebar rail style below the Biomes/Structures/Seeds buttons and can be hidden via the new "Show seed search button" setting (default on)

## 1.4.2

### Fixes

- Fixed preview map not loading when dragged to certain positions — queue handshake is now locked, and the viewport force-requeues unsampled areas when sampling is idle
- Fixed initialization failure from cross-loader configs — dimension identifiers with null namespace/path are now treated as unset and rewritten as `"namespace:path"` strings

### UI

- Preview page: unified Biomes / Structures / Seeds rail buttons (gray-to-black translucent theme); selected tab no longer darkens its background — marked only by outline and full-white text, all three identical at rest

## 1.4.1

### Fixes

- Fixed a `NullPointerException` crash caused by GLFW mouse button state polling (thanks to the contributor of PR #1)

### Improvements

- Removed unused code

## 1.4.0

### New Features

- Added a biome colour intelligence system: biomes the mod does not know (from mods and datapacks) are no longer coloured by a hash, but resolved through layers — exact overrides, climate category, vegetation affinity, elevation and colour temperature — with Lab-space blending between adjacent biomes
- Added a biome rarity analyzer: rare biomes in the viewport get a luminance boost and a warm halo, common biomes are dimmed slightly, so rare biomes stand out without making the map unreadable
- Added per-noise-type colour gradients: temperature, humidity, continentalness, erosion, depth, weirdness and peaks/valleys each get their own gradient, with sRGB or Lab interpolation and optional banding for a topographic look
- Biome display names are generated from the biome id (for example `snowy_taiga` → `Snowy Taiga`)

### Improvements

- **The scroll wheel now zooms the map by default** — it used to move along the Y axis. `Ctrl`+scroll always zooms and `Alt`+scroll always adjusts Y, whatever the setting says
- Chunks nearest the viewport centre are now sampled first (centre-out spiral instead of a random order), which makes the map appear to load several times faster
- Fixed the early-abort guard preventing the same range from being re-queued, for example after a drag
- More accurate world-to-screen mapping (double-precision arithmetic)

## 1.3.5

### New Features

- Added terrain map export: export the terrain distribution of the current seed as PNG images from the General settings page, with a configurable radius and blocks-per-pixel resolution, classification into 9 terrain categories, an optional contour overlay and progress with remaining-time estimate; saved to `config/world_preview/terrain_exports/`
- Added hillshade rendering: simulated sun illumination over the heightmap view for a better sense of 3D terrain, with configurable azimuth (0-360°), altitude (0-90°), ambient light (0-1) and vertical exaggeration (0.1-5.0)
- Added contour lines: topographic contours over the heightmap preview, with a configurable interval (5-64 blocks) and optional minor lines at one fifth of the interval (major lines brown, minor lines olive)
- Added config backup, loading and version migration

### Improvements

- Large-area terrain export is much faster thanks to a lightweight biome sampler

## 1.3.4

### New Features

- Added spawn pin mode: a `Spawn` button next to the settings button in the world creation preview toggles pin placement; left-click the map to place or move the pin, right-click to remove it
- The pin's coordinates are applied as the world's spawn point when the world is created — no cheats required
- The spawn pin is only available during world creation, not in the in-game preview

### Improvements

- The button text reflects the current state (`Spawn` / `Spawn ✓`), and the pin coordinates are saved to config and restored when the world creation screen is reopened

## 1.3.3

### New Features

- Added seed search (first version): right-click a biome in the biome list to search seeds containing it
- Added a visible-block count to the biome list, and a toggle for the analysis button
- Added search settings (minimum area share, maximum distance)

### Improvements

- Reorganized the settings page into sections

### Fixes

- Fixed feedback on the PNG export button