## 1.5.3

### Fixes

- Fixed the analysis screen layout on small windows: the profile chart no longer overlaps the footer buttons, the chart tabs no longer overlap the profile-direction button, and the overview panel now extends to the bottom of the screen instead of leaving dead space above the footer
- Fixed a phantom selection band rendering at the right edge of the region coordinate fields
- Fixed the map frame touching the seed row: the bottom edge of the map now leaves a small gap above the refresh/save buttons instead of overlapping their top edge

### Improvements

- Noise render modes (temperature, humidity, continentalness, ...) now draw smooth perceptual color gradients instead of coarse posterized color bands; the temperature, humidity, continentalness and erosion ramps are anchored to the vanilla biome climate bands, so colors line up with real biome boundaries
- The "Analysis" button now uses the same sidebar rail style as the Biomes/Structures/Seeds buttons and sits directly below the Seeds button in the sidebar, instead of the old plain button style floating over the map
- The world analysis screen has been rebuilt in the panelized style of the seed search screen: region coordinates now really define the analyzed area (with shrink/invalid feedback), a stats panel with progress bar and spawn-score breakdown, a biome-colored terrain cross-section with sea-level line, a height histogram and biome/terrain share tabs, map box-selection for the region, structure distances, and richer CSV/JSON reports
- Fixed the spawn score ignoring nearby structures, mis-scaled slope scoring and distorted water share when the preview layer was underground
- Map zooming was reworked: the mouse wheel and the settings screen now share one zoom ladder (16 down to 1 pixels per chunk - the old 64/32 levels were pure sub-block pixel upscaling with no extra detail and were removed), zooming across the same sampling density re-scales instantly instead of rebuilding the whole map, the wheel now also reaches the 2 and 1 px/chunk overview levels (the widest world view, sampled slowest), and reaching a limit shows a "fully zoomed in/out" hint instead of silently ignoring the scroll
- A permanent scale bar was added to the bottom-left corner of the map, so the current zoom level is always readable without waiting for the fading HUD message. It is interactive: next to the block-distance readout sits a tick slider over the zoom ladder - click or drag it to jump between zoom levels in real time (same instant/rebuild routing as the wheel), and clicks on it no longer fall through to the map underneath

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
- Added a seed comparison screen: compares the current seed with up to three saved seeds by biome diversity, water share, most common biome and spawn score
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