# CHANGELOG — World Preview Fork

## 1.5.0

### Features — Seed search rework (1.21.11-fabric)

- **Multi-criteria seed search**: a new Seed Search screen combines a biome
  criterion (selected in the biome list) with a structure criterion
  (any random-spread structure such as villages, bastions, ancient cities, …).
  All criteria must pass for a seed to hit.
- **Structure-based seed search** with a lightweight vanilla `/locate`-style
  probe: candidate chunks are computed from structure placement math and
  verified with `Structure.findValidGenerationPoint` per candidate seed — no
  dummy server or chunk generation required. Open it via the new *Seed Search*
  button or by right-clicking a structure in the structures list (auto-starts).
- **Ranked multi-hit results**: the advanced search collects up to 10 seeds
  (scored by biome coverage and structure proximity, best first) instead of
  stopping at the first hit.
- **Search history & favorites** persisted to
  `config/world_preview/search-history.json`: click a row to re-apply a seed,
  shift+right-click to favorite it, right-click to delete it (history entries).
- Configurable search limits: biome min-area, structure distance
  (128–8192 blocks), max attempts and number of results.
- Search anchor selectable between the current map center and the world origin.

### Features — Map interactions (1.21.11-fabric)

- **Map waypoints**: named, colored pins persisted per seed/dimension in
  `config/world_preview/waypoints.json`. Toggle the new *Waypoints* button,
  left-click the map to place a pin via the naming dialog, and right-click an
  existing pin to delete it.
- **Structure locate**: double-click a structure in the structures list to
  center the map on its nearest rendered instance.
- **Measure tool**: two clicks on the map measure distance and axis deltas,
  drawn as an overlay; right-click clears the measurement.

### Features — Export & analysis reports (1.21.11-fabric)

- **Analysis report export**: the world analysis screen gains an *Export Report*
  button that writes the biome share table as CSV plus a JSON summary
  (seed, dimension, region, coverage, height/slope statistics) into
  `config/world_preview/reports/`.
- **Multi-dimension terrain export**: the terrain export screen can batch-export
  a terrain map for every available dimension of the current seed with the same
  settings, sequentially, with live per-dimension progress and cancel support.
- **Grid overlay in terrain maps**: exported terrain maps can optionally draw a
  block-coordinate grid (spec-level option, off by default; not yet exposed in
  the UI).

### Features — Spawn point & seed analysis (1.21.11-fabric)

- **Spawn score in the analysis screen**: once a region analysis finishes, the
  metrics panel shows a spawn quality score (0–100) weighted from flatness,
  slope and the water share (ocean/river/deep-ocean biome tags), plus up to
  three translated reasons (much/little water, rough/flat terrain, steep slope,
  good/poor verdict).
- **Top-biomes rarity display**: the analysis panel also lists the five most
  common biomes of the analyzed region with their share percentage and a star
  rating (3 stars = rare, below 1% share).
- **Seed comparison screen**: opened from a new *Compare Seeds* button in the
  seed search screen; compares the current seed plus up to three saved seeds by
  sampling the biome composition in a square around the world origin
  (radius 512, step 16, y=64). Per seed it reports biome diversity, water
  share, the most common biome with its share and a spawn score, with per-row
  progress and cancel support; non-numeric or unsampleable seeds are marked
  unavailable.

### Changes — Biome search rework (1.21.11-fabric)

- **Biome criteria are now chosen in a filterable multi-select picker** inside
  the Seed Search screen (color chips, a gray `[cave]` tag on cave biomes and a
  cave filter toggle). Up to 4 biomes can be combined into one ANY-of group
  criterion ("any jungle variant"), evaluated together for area coverage and
  proximity.
- **Biome groups support a per-search max distance**: the nearest matching
  point of the group must lie within the configured block radius of the search
  anchor (0 = no distance limit), with a proximity bonus for closer hits.
- **Right-clicking a biome in the biome list now opens the Seed Search screen
  pre-filled with that biome and auto-starts the search**, consistent with the
  structure right-click flow (a running search is cancelled first).
- Removed the old inline list-status search flow (status rows on the biome
  list and its cancel/inline-search right-click behavior).

### UI — Seed search screen & button (1.21.11-fabric)

- Added a Back button to the Seed Search screen (previously only ESC closed it).
- The Seed Search screen layout is now height-adaptive: Start/Stop/Compare are
  pinned to a footer row together with the hits slider, and the biome picker
  absorbs the remaining space, so all controls stay visible on small GUI scales
  (e.g. 3x/4x) where the Start Search button could previously fall off-screen.
- The Seed Search screen is now compacted: the six criteria controls are laid
  out three per row across the full screen width in two rows, the biome picker
  is narrowed to 1.5 button widths, and the results view moved beside it, so
  no widget overlaps another at any GUI size.
- The *Seed Search* button now uses the sidebar's translucent rail style
  (matching the Biomes/Structures/Seeds buttons) and sits directly below them;
  it can be hidden via the new General setting *Show seed search button*
  (default on).

### Notes

- Structures using concentric-ring placement (strongholds) are not supported
  by the lightweight probe yet; their criteria never match.

## 1.4.2

### Fixes

- Fixed preview map not loading when dragged to certain positions — queue handshake is now locked, and the viewport force-requeues unsampled areas when sampling is idle
- Fixed initialization failure from cross-loader configs — dimension identifiers with null namespace/path are now treated as unset and rewritten as `"namespace:path"` strings

### UI

- Preview page: unified Biomes / Structures / Seeds rail buttons (gray-to-black translucent theme); selected tab no longer darkens its background — marked only by outline and full-white text, all three identical at rest