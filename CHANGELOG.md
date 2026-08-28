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

### Notes

- Structures using concentric-ring placement (strongholds) are not supported
  by the lightweight probe yet; their criteria never match.

## 1.4.2

### Fixes

- Fixed preview map not loading when dragged to certain positions — queue handshake is now locked, and the viewport force-requeues unsampled areas when sampling is idle
- Fixed initialization failure from cross-loader configs — dimension identifiers with null namespace/path are now treated as unset and rewritten as `"namespace:path"` strings

### UI

- Preview page: unified Biomes / Structures / Seeds rail buttons (gray-to-black translucent theme); selected tab no longer darkens its background — marked only by outline and full-white text, all three identical at rest