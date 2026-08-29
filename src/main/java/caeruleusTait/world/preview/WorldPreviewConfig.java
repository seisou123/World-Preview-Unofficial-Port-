// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview;

import java.util.ArrayList;
import java.util.List;

public class WorldPreviewConfig {

    public int configVersion = 4;
    public List<String> savedSeeds = new ArrayList<>();

    // Analysis defaults. Older config files may omit these fields.
    public int analysisDefaultSampleStep = 1;
    public long analysisMaxRegionBlocks = 4_000_000L;

    public boolean showInPauseMenu = true;
    public boolean showPlayer = true;
    public boolean showControls = true;
    public boolean showFrameTime = false;
    public boolean showMinimap = false;
    public boolean showStatistics = false;
    public boolean showCoordinates = false;
    public boolean showBiomeCounts = false;
    public boolean showAnalysisButton = false;
    public boolean showSeedSearchButton = true;
    public int searchMinAreaPercent = 0;
    public int searchMaxDistance = 0;
    public boolean scrollWheelZooms = true;
    public boolean enablePreload = true;
    public boolean preloadOnlyWhenIdle = true;
    public int preloadRadius = 128;
    public boolean buildFullVertChunk = false;
    public boolean backgroundSampleVertChunk = false;
    public boolean sampleStructures = false;
    public boolean sampleHeightmap = false;
    public boolean sampleIntersections = false;
    public boolean storeNoiseSamples = false;
    public boolean usePerNoiseTypeGradients = true;
    public int heightmapMinY = 32;
    public int heightmapMaxY = 255;
    public boolean onlySampleInVisualRange = true;
    public boolean cacheInGame = true;
    public boolean cacheInNew = false;
    public boolean enableCompression = true;
    public String colorMap = "world_preview:inferno";

    // Spawn override: when the player sets a spawn pin on the preview map,
    // these coordinates are stored and applied when the world is created.
    public boolean spawnOverrideEnabled = false;
    public int spawnOverrideX = 0;
    public int spawnOverrideZ = 0;

    // Hillshade rendering settings
    public boolean enableHillshade = false;
    public float hillshadeAzimuth = 315f;
    public float hillshadeAltitude = 45f;
    public float hillshadeAmbient = 0.3f;
    public float hillshadeExaggeration = 1.0f;

    // Contour line settings (preview page)
    public boolean enableContours = false;
    public int contourInterval = 10;
    public boolean contourMinorLines = true;

    // Compatibility configuration
    public boolean autoDetectMods = true;
    public List<String> disabledCompatMods = new ArrayList<>();
    public String activeCompatProfile = "auto";

    private int numThreads = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);

    public int numThreads() {
        return numThreads;
    }

    public void setNumThreads(int numThreads) {
        this.numThreads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), numThreads));
    }

    public static WorldPreviewConfig defaults() {
        return new WorldPreviewConfig();
    }

    public WorldPreviewConfig copy() {
        WorldPreviewConfig copy = new WorldPreviewConfig();
        copy.apply(this);
        return copy;
    }

    public void apply(WorldPreviewConfig source) {
        if (source == null) {
            throw new IllegalArgumentException("source");
        }
        configVersion = source.configVersion;
        savedSeeds = source.savedSeeds == null ? new ArrayList<>() : new ArrayList<>(source.savedSeeds);
        analysisDefaultSampleStep = source.analysisDefaultSampleStep;
        analysisMaxRegionBlocks = source.analysisMaxRegionBlocks;
        showInPauseMenu = source.showInPauseMenu;
        showPlayer = source.showPlayer;
        showControls = source.showControls;
        showFrameTime = source.showFrameTime;
        showMinimap = source.showMinimap;
        showStatistics = source.showStatistics;
        showCoordinates = source.showCoordinates;
        showBiomeCounts = source.showBiomeCounts;
        showAnalysisButton = source.showAnalysisButton;
        showSeedSearchButton = source.showSeedSearchButton;
        searchMinAreaPercent = source.searchMinAreaPercent;
        searchMaxDistance = source.searchMaxDistance;
        scrollWheelZooms = source.scrollWheelZooms;
        enablePreload = source.enablePreload;
        preloadOnlyWhenIdle = source.preloadOnlyWhenIdle;
        preloadRadius = source.preloadRadius;
        buildFullVertChunk = source.buildFullVertChunk;
        backgroundSampleVertChunk = source.backgroundSampleVertChunk;
        sampleStructures = source.sampleStructures;
        sampleHeightmap = source.sampleHeightmap;
        sampleIntersections = source.sampleIntersections;
        storeNoiseSamples = source.storeNoiseSamples;
        usePerNoiseTypeGradients = source.usePerNoiseTypeGradients;
        heightmapMinY = source.heightmapMinY;
        heightmapMaxY = source.heightmapMaxY;
        onlySampleInVisualRange = source.onlySampleInVisualRange;
        cacheInGame = source.cacheInGame;
        cacheInNew = source.cacheInNew;
        enableCompression = source.enableCompression;
        colorMap = source.colorMap;
        spawnOverrideEnabled = source.spawnOverrideEnabled;
        spawnOverrideX = source.spawnOverrideX;
        spawnOverrideZ = source.spawnOverrideZ;
        enableHillshade = source.enableHillshade;
        hillshadeAzimuth = source.hillshadeAzimuth;
        hillshadeAltitude = source.hillshadeAltitude;
        hillshadeAmbient = source.hillshadeAmbient;
        hillshadeExaggeration = source.hillshadeExaggeration;
        enableContours = source.enableContours;
        contourInterval = source.contourInterval;
        contourMinorLines = source.contourMinorLines;
        autoDetectMods = source.autoDetectMods;
        disabledCompatMods = source.disabledCompatMods == null
                ? new ArrayList<>() : new ArrayList<>(source.disabledCompatMods);
        activeCompatProfile = source.activeCompatProfile;
        setNumThreads(source.numThreads);
    }

    public WorldPreviewConfig normalized() {
        WorldPreviewConfig normalized = copy();
        normalized.savedSeeds.removeIf(seed -> seed == null);
        normalized.analysisDefaultSampleStep = Math.max(1, Math.min(256, normalized.analysisDefaultSampleStep));
        normalized.analysisMaxRegionBlocks = Math.max(1L, Math.min(4_000_000L, normalized.analysisMaxRegionBlocks));
        normalized.preloadRadius = Math.max(0, normalized.preloadRadius);
        normalized.searchMinAreaPercent = Math.max(0, Math.min(100, normalized.searchMinAreaPercent));
        normalized.searchMaxDistance = Math.max(0, normalized.searchMaxDistance);
        normalized.hillshadeAzimuth = Math.max(0f, Math.min(360f, normalized.hillshadeAzimuth));
        normalized.hillshadeAltitude = Math.max(0f, Math.min(90f, normalized.hillshadeAltitude));
        normalized.hillshadeAmbient = Math.max(0f, Math.min(1f, normalized.hillshadeAmbient));
        normalized.hillshadeExaggeration = Math.max(0.1f, Math.min(5f, normalized.hillshadeExaggeration));
        normalized.contourInterval = Math.max(1, Math.min(64, normalized.contourInterval));
        if (normalized.heightmapMinY > normalized.heightmapMaxY) {
            int min = normalized.heightmapMinY;
            normalized.heightmapMinY = normalized.heightmapMaxY;
            normalized.heightmapMaxY = min;
        }
        if (normalized.colorMap == null || normalized.colorMap.isBlank()) {
            normalized.colorMap = defaults().colorMap;
        }
        if (normalized.disabledCompatMods == null) {
            normalized.disabledCompatMods = new ArrayList<>();
        }
        if (normalized.activeCompatProfile == null || normalized.activeCompatProfile.isBlank()) {
            normalized.activeCompatProfile = "auto";
        }
        normalized.setNumThreads(normalized.numThreads);
        return normalized;
    }

    public void validate() {
        if (savedSeeds == null
                || analysisDefaultSampleStep < 1 || analysisDefaultSampleStep > 256
                || analysisMaxRegionBlocks < 1 || analysisMaxRegionBlocks > 4_000_000L
                || preloadRadius < 0 || heightmapMinY > heightmapMaxY
                || colorMap == null || colorMap.isBlank()
                || activeCompatProfile == null || activeCompatProfile.isBlank()
                || disabledCompatMods == null || numThreads < 1) {
            throw new IllegalArgumentException("Invalid world preview config");
        }
    }
}
