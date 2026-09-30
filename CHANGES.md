# CHANGES — World Preview Fork (26.2)

This file lists all files modified from the original World Preview project
(https://modrinth.com/mod/world-preview) by Caeruleus Draconis & Taiterio,
licensed under Apache-2.0.

Each modified file carries a prominent header notice referencing this file.

---

## Modified Java Source Files (59)

These files existed in the original project and have been modified for this fork.

### Core
- `fabric/src/main/java/caeruleusTait/world/preview/WorldPreview.java`
- `neoforge/src/main/java/caeruleusTait/world/preview/WorldPreview.java`
- `common/src/main/java/caeruleusTait/world/preview/WorldPreviewConfig.java`
- `common/src/main/java/caeruleusTait/world/preview/RenderSettings.java`

### Backend — Color
- `common/src/main/java/caeruleusTait/world/preview/backend/color/BaseMultiJsonResourceReloadListener.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/BiomeColorMapReloadListener.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/ColorMap.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/ColormapReloadListener.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/HeightmapPresetReloadListener.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/PreviewData.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/PreviewMappingData.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/color/StructureMapReloadListener.java`

### Backend — Storage
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewBlock.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewLevel.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSection.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionCompressed.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionFull.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionHalf.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionQuarter.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewStorage.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/storage/PreviewStorageCacheManager.java`

### Backend — Stubs
- `common/src/main/java/caeruleusTait/world/preview/backend/stubs/DummyMinecraftServer.java`
- `common/src/main/java/caeruleusTait/world/preview/backend/stubs/DummyPlayerList.java`
- `fabric/src/main/java/caeruleusTait/world/preview/backend/stubs/DummyServerLevelData.java`
- `neoforge/src/main/java/caeruleusTait/world/preview/backend/stubs/DummyServerLevelData.java`

### Backend — Worker
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

### Backend — WorkManager
- `common/src/main/java/caeruleusTait/world/preview/backend/WorkManager.java`

### Client — Core
- `fabric/src/main/java/caeruleusTait/world/preview/client/WorldPreviewClient.java`
- `neoforge/src/main/java/caeruleusTait/world/preview/client/WorldPreviewClient.java`
- `common/src/main/java/caeruleusTait/world/preview/client/WorldPreviewComponents.java`

### Client — GUI Screens
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/InGamePreviewScreen.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/PreviewCacheLoadingScreen.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/PreviewContainer.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/PreviewTab.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/screens/settings/BiomesTab.java`

### Client — GUI Widgets
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/ColorChooser.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/OldStyleImageButton.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/PreviewDisplay.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/ToggleButton.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/WGLabel.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/BaseObjectSelectionList.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/BiomesList.java`
- `common/src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/StructuresList.java`

### Mixin
- `common/src/main/java/caeruleusTait/world/preview/mixin/ChunkGeneratorStructureStateMixin.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/NoiseBasedAquiferMixin.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/NoiseChunkAccessor.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/StructureTemplatePaletteMixin.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/client/CreateWorldScreenAccessor.java`
- `common/src/main/java/caeruleusTait/world/preview/mixin/client/CreateWorldScreenMixin.java`

---

## Modified Resource Files (8)

- `fabric/src/main/resources/fabric.mod.json`
- `fabric/src/main/resources/world_preview.accesswidener`
- `fabric/src/main/resources/world_preview.mixins.json`
- `neoforge/src/main/resources/world_preview.mixins.json`
- `common/src/main/resources/assets/world_preview/lang/en_us.json`
- `common/src/main/resources/assets/world_preview/lang/pt_pt.json`
- `common/src/main/resources/assets/world_preview/lang/ru_ru.json`
- `common/src/main/resources/assets/world_preview/lang/zh_cn.json`

---

## Modified Repository and Documentation Files (3)

- `README.md` — rewritten for this fork: supported Minecraft versions and loaders, the
  scroll-wheel behaviour, and a per-item account of what this fork changed relative to upstream
- `.github/ISSUE_TEMPLATE/BUG-REPORT.yml` — version and loader drop-downs updated to the
  versions and loaders this fork actually ships
- `.github/ISSUE_TEMPLATE/FEATURE-REQUEST.yml` — description updated for the fork

---

## Summary of Changes

- Upgraded from Minecraft 1.21 to 26.2 (Fabric Loader 0.19.3, Loom 1.17.21, NeoForge via ModDevGradle 2.0.143, Java 25)
- Multi-loader build from one shared source tree: loader-neutral code in `common/`, loader-specific code in the `fabric/` and `neoforge/` modules (the 1.20.1 branch of this fork uses a `forge/` module instead)
- Added mod compatibility framework (`compat/` package)
- Added world analysis engine (`backend/analysis/` package)
- Added domain-driven architecture (`domain/` and `infra/` packages)
- Added minimap overlay and statistics display
- Added a zoom ladder (16, 8, 4, 2 or 1 pixels per chunk; 4 by default), shared by the mouse wheel and the settings screen
- Added preload system
- Reworked settings UI to sidebar + page architecture
- Improved thread safety in WorkManager (volatile fields, session epochs)
- Improved rendering pipeline for MC 26.2
- Added a JUnit 5 test suite (62 shared test classes in `common/`, plus loader-specific tests in the loader modules)
- Performance: thread pre-starting, optimized batch sizing, adaptive render throttling
