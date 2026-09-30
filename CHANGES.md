# CHANGES — World Preview Fork (1.20.1)

This file lists the files of the original World Preview project
(https://modrinth.com/mod/world-preview) by Caeruleus Draconis & Taiterio,
licensed under Apache-2.0, that this fork modifies, and records where the
fork's own files live.

Base: the upstream `1.20.1` branch at commit `68bcef3` (2026-04-04). Every
upstream `src/main` file was compared against this branch byte-for-byte after
normalizing line endings.

This branch is a multi-loader build: loader-neutral sources live in `common/`
and are compiled unchanged into the Fabric (Loom) and Forge (ForgeGradle 6)
modules; loader-specific classes live in `fabric/` and `forge/`. Paths below
use that layout.

Each modified Java source file carries a prominent header notice referencing this file.

---

## Modified upstream files (77)

(77 listed copies from 76 distinct upstream files: the shared `world_preview.mixins.json` is
listed once per loader module.)

### `(root)/` (5)
- `fabric/src/main/resources/fabric.mod.json`
- `fabric/src/main/resources/world_preview.accesswidener`
- `fabric/src/main/resources/world_preview.mixins.json`
- `forge/src/main/resources/pack.mcmeta`
- `forge/src/main/resources/world_preview.mixins.json`

### `META-INF/` (2)
- `forge/src/main/resources/META-INF/accesstransformer.cfg`
- `forge/src/main/resources/META-INF/mods.toml`

### `assets/world_preview/` (1)
- `common/src/main/resources/assets/world_preview/icon.png`

### `assets/world_preview/lang/` (4)
- `common/src/main/resources/assets/world_preview/lang/en_us.json`
- `common/src/main/resources/assets/world_preview/lang/pt_pt.json`
- `common/src/main/resources/assets/world_preview/lang/ru_ru.json`
- `common/src/main/resources/assets/world_preview/lang/zh_cn.json`

### `caeruleusTait/world/preview/` (4)
- `common/src/main/java/caeruleusTait/world/preview/RenderSettings.java`
- `common/src/main/java/caeruleusTait/world/preview/WorldPreviewConfig.java`
- `fabric/src/main/java/caeruleusTait/world/preview/WorldPreview.java`
- `forge/src/main/java/caeruleusTait/world/preview/WorldPreview.java`

### `caeruleusTait/world/preview/backend/` (1)
- `common/src/main/java/caeruleusTait/world/preview/backend/WorkManager.java`

### `caeruleusTait/world/preview/backend/color/` (8)
- `common/src/main/java/caeruleusTait/world/preview/backend/color/BaseMultiJsonResourceReloadListener.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/BiomeColorMapReloadListener.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/ColorMap.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/ColormapReloadListener.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/HeightmapPresetReloadListener.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/PreviewData.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/PreviewMappingData.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/StructureMapReloadListener.java`

### `caeruleusTait/world/preview/backend/storage/` (10)
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewBlock.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewLevel.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSection.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionCompressed.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionFull.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionHalf.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionQuarter.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionStructure.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewStorage.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewStorageCacheManager.java`

### `caeruleusTait/world/preview/backend/stubs/` (2)
- `common/src/main/java/caeruleusTait/world/preview/backend/stubs/DummyMinecraftServer.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/stubs/DummyPlayerList.java`

### `caeruleusTait/world/preview/backend/worker/` (11)
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/FullChunkWorkUnit.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/HeightmapWorkUnit.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/IntersectionWorkUnit.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/LayerChunkWorkUnit.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/SampleUtils.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/SlowHeightmapWorkUnit.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/SlowIntersectionWorkUnit.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/StructStartWorkUnit.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/WorkBatch.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/WorkResult.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/worker/WorkUnit.java`

### `caeruleusTait/world/preview/client/` (3)
- `common/src/main/java/caeruleusTait/world/preview/client/WorldPreviewComponents.java`
- `fabric/src/main/java/caeruleusTait/world/preview/client/WorldPreviewClient.java`
- `forge/src/main/java/caeruleusTait/world/preview/client/WorldPreviewClient.java`

### `caeruleusTait/world/preview/client/gui/` (1)
- `common/src/main/java/caeruleusTait/world/preview/client/gui/PreviewDisplayDataProvider.java`

### `caeruleusTait/world/preview/client/gui/screens/` (4)
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/InGamePreviewScreen.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/PreviewCacheLoadingScreen.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/PreviewContainer.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/PreviewTab.java`

### `caeruleusTait/world/preview/client/gui/screens/settings/` (1)
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/settings/BiomesTab.java`

### `caeruleusTait/world/preview/client/gui/widgets/` (4)
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/ColorChooser.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/PreviewDisplay.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/ToggleButton.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/WGLabel.java`

### `caeruleusTait/world/preview/client/gui/widgets/lists/` (3)
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/BaseObjectSelectionList.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/BiomesList.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/StructuresList.java`

### `caeruleusTait/world/preview/mixin/` (5)
- `common/src/main/java/caeruleusTait/world/preview/mixin/ChunkGeneratorStructureStateMixin.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/NoiseBasedAquiferMixin.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/NoiseChunkAccessor.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/StructureTemplatePaletteMixin.java`
- `fabric/src/main/java/caeruleusTait/world/preview/mixin/ReloadableServerResourcesMixin.java`

### `caeruleusTait/world/preview/mixin/client/` (6)
- `common/src/main/java/caeruleusTait/world/preview/mixin/client/CreateWorldScreenAccessor.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/client/CreateWorldScreenMixin.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/client/PauseScreenMixin.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/client/ScreenAccessor.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/client/TabManagerMixin.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/client/WorldTabMixin.java`

### `data/c/tags/worldgen/structure/` (1)
- `common/src/main/resources/data/c/tags/worldgen/structure/display_on_map_by_default.json`

### `data/c/worldgen/` (1)
- `common/src/main/resources/data/c/worldgen/structure_icons.json`

---

## Removed or replaced upstream files (12)

- The six upstream settings screens were replaced by the fork's settings
  framework (`client/gui/screens/settings/`): `SettingsScreen.java`,
  `CacheTab.java`, `DimensionsTab.java`, `GeneralTab.java`, `HeightmapTab.java`,
  `SamplingTab.java`
- `WGCheckbox.java`, `SelectionSlider.java` and
  `AbstractSelectionListHolder.java` were replaced by the fork's widget set
  (`WPCheckbox` and related classes)
- `SeedsList.java` was superseded by the seed-hub screens
- `mixin/ReloadableServerResourcesMixin.java` no longer exists on Forge —
  reload listeners are registered through `AddReloadListenerEvent`; the Fabric
  copy lives in the `fabric` module (counted under Modified via that copy, not
  double-counted here)
- `assets/world_preview/shaders/core/hsv.json` / `hsv.fsh`: an unused
  core-shader pair (the hue bar is drawn with `fillGradient`) — not shipped

---

## Added by this fork (142 files)

Fork-only Java sources, grouped by directory (counts in parentheses):

- `caeruleusTait/world/preview/` (1)
- `caeruleusTait/world/preview/backend/analysis/` (30)
- `caeruleusTait/world/preview/backend/color/` (4)
- `caeruleusTait/world/preview/backend/export/` (6)
- `caeruleusTait/world/preview/backend/sampler/` (1)
- `caeruleusTait/world/preview/backend/storage/` (5)
- `caeruleusTait/world/preview/backend/stubs/` (2)
- `caeruleusTait/world/preview/backend/terrain/` (2)
- `caeruleusTait/world/preview/backend/worker/` (1)
- `caeruleusTait/world/preview/client/gui/` (1)
- `caeruleusTait/world/preview/client/gui/input/` (2)
- `caeruleusTait/world/preview/client/gui/screens/` (11)
- `caeruleusTait/world/preview/client/gui/screens/settings/` (13)
- `caeruleusTait/world/preview/client/gui/widgets/` (17)
- `caeruleusTait/world/preview/client/gui/widgets/lists/` (3)
- `caeruleusTait/world/preview/compat/` (6)
- `caeruleusTait/world/preview/config/` (3)
- `caeruleusTait/world/preview/domain/preview/accuracy/` (5)
- `caeruleusTait/world/preview/domain/session/` (6)
- `caeruleusTait/world/preview/domain/task/` (8)
- `caeruleusTait/world/preview/domain/ui/` (7)
- `caeruleusTait/world/preview/domain/waypoint/` (2)
- `caeruleusTait/world/preview/infra/minecraft/` (2)
- `caeruleusTait/world/preview/mixin/` (1)
- `caeruleusTait/world/preview/mixin/client/` (2)
- `caeruleusTait/world/preview/util/` (1)

---

A machine-generated comparison used the rules above: upstream loader variants
(`*.java.fabric` / `*.java.forge`, `fabric.mod.json.fabric`,
`pack.mcmeta.forge`, ...) were matched against the corresponding loader module;
line endings were normalized before comparison. Binary assets (structure
icons, player/bed markers, the mod icon) are included in the comparison.
