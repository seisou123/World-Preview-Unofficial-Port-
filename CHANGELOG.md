# CHANGELOG — World Preview Fork

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
