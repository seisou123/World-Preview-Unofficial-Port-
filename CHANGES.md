# CHANGES — World Preview Fork (1.21.11)

This file lists all files modified from the original World Preview project
(https://modrinth.com/mod/world-preview) by Caeruleus Draconis & Taiterio,
licensed under Apache-2.0.

Each modified file carries a prominent header notice referencing this file.

---

## Modified Java Source Files (57)

These files existed in the original project and have been modified for this fork.

### Core
- `src/main/java/caeruleusTait/world/preview/WorldPreview.java`
- `src/main/java/caeruleusTait/world/preview/WorldPreviewConfig.java`
- `src/main/java/caeruleusTait/world/preview/RenderSettings.java`

### Backend — Color
- `src/main/java/caeruleusTait/world/preview/backend/color/BaseMultiJsonResourceReloadListener.java`
- `src/main/java/caeruleusTait/world/preview/backend/color/BiomeColorMapReloadListener.java`
- `src/main/java/caeruleusTait/world/preview/backend/color/ColorMap.java`
- `src/main/java/caeruleusTait/world/preview/backend/color/ColormapReloadListener.java`
- `src/main/java/caeruleusTait/world/preview/backend/color/HeightmapPresetReloadListener.java`
- `src/main/java/caeruleusTait/world/preview/backend/color/PreviewData.java`
- `src/main/java/caeruleusTait/world/preview/backend/color/PreviewMappingData.java`
- `src/main/java/caeruleusTait/world/preview/backend/color/StructureMapReloadListener.java`

### Backend — Storage
- `src/main/java/caeruleusTait/world/preview/backend/storage/PreviewBlock.java`
- `src/main/java/caeruleusTait/world/preview/backend/storage/PreviewLevel.java`
- `src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSection.java`
- `src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionCompressed.java`
- `src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionFull.java`
- `src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionHalf.java`
- `src/main/java/caeruleusTait/world/preview/backend/storage/PreviewSectionQuarter.java`
- `src/main/java/caeruleusTait/world/preview/backend/storage/PreviewStorage.java`
- `src/main/java/caeruleusTait/world/preview/backend/storage/PreviewStorageCacheManager.java`

### Backend — Stubs
- `src/main/java/caeruleusTait/world/preview/backend/stubs/DummyMinecraftServer.java`
- `src/main/java/caeruleusTait/world/preview/backend/stubs/DummyPlayerList.java`
- `src/main/java/caeruleusTait/world/preview/backend/stubs/DummyServerLevelData.java`

### Backend — Worker
- `src/main/java/caeruleusTait/world/preview/backend/worker/FullChunkWorkUnit.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/HeightmapWorkUnit.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/IntersectionWorkUnit.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/LayerChunkWorkUnit.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/SampleUtils.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/SlowHeightmapWorkUnit.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/SlowIntersectionWorkUnit.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/StructStartWorkUnit.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/WorkBatch.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/WorkResult.java`
- `src/main/java/caeruleusTait/world/preview/backend/worker/WorkUnit.java`

### Backend — WorkManager
- `src/main/java/caeruleusTait/world/preview/backend/WorkManager.java`

### Client — Core
- `src/main/java/caeruleusTait/world/preview/client/WorldPreviewClient.java`
- `src/main/java/caeruleusTait/world/preview/client/WorldPreviewComponents.java`

### Client — GUI Screens
- `src/main/java/caeruleusTait/world/preview/client/gui/screens/InGamePreviewScreen.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/screens/PreviewCacheLoadingScreen.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/screens/PreviewContainer.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/screens/PreviewTab.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/screens/settings/BiomesTab.java`

### Client — GUI Widgets
- `src/main/java/caeruleusTait/world/preview/client/gui/widgets/ColorChooser.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/widgets/OldStyleImageButton.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/widgets/PreviewDisplay.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/widgets/ToggleButton.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/widgets/WGLabel.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/BaseObjectSelectionList.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/BiomesList.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/SeedsList.java`
- `src/main/java/caeruleusTait/world/preview/client/gui/widgets/lists/StructuresList.java`

### Mixin
- `src/main/java/caeruleusTait/world/preview/mixin/ChunkGeneratorStructureStateMixin.java`
- `src/main/java/caeruleusTait/world/preview/mixin/NoiseBasedAquiferMixin.java`
- `src/main/java/caeruleusTait/world/preview/mixin/NoiseChunkAccessor.java`
- `src/main/java/caeruleusTait/world/preview/mixin/StructureTemplatePaletteMixin.java`
- `src/main/java/caeruleusTait/world/preview/mixin/client/CreateWorldScreenAccessor.java`
- `src/main/java/caeruleusTait/world/preview/mixin/client/CreateWorldScreenMixin.java`

---

## Modified Resource Files (7)

- `src/main/resources/fabric.mod.json`
- `src/main/resources/world_preview.accesswidener`
- `src/main/resources/world_preview.mixins.json`
- `src/main/resources/assets/world_preview/lang/en_us.json`
- `src/main/resources/assets/world_preview/lang/pt_pt.json`
- `src/main/resources/assets/world_preview/lang/ru_ru.json`
- `src/main/resources/assets/world_preview/lang/zh_cn.json`

---

## Summary of Changes

- Upgraded from Minecraft 1.21 to 1.21.11 (Fabric Loader 0.19.3, Loom 1.17)
- Removed Forge multi-loader support; Fabric-only
- Added mod compatibility framework (`compat/` package)
- Added world analysis engine (`backend/analysis/` package)
- Added domain-driven architecture (`domain/`, `infra/`, `viewmodel/` packages)
- Added minimap overlay and statistics display
- Added zoom levels (32px and 64px per chunk)
- Added preload system
- Reworked settings UI to sidebar + page architecture
- Improved thread safety in WorkManager (volatile fields, session epochs)
- Improved rendering pipeline for MC 1.21.11
- Added 35 JUnit 5 test files
- Performance: thread pre-starting, optimized batch sizing, adaptive render throttling
