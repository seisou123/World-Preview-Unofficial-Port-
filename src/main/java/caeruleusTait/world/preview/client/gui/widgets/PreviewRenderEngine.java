package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.backend.terrain.ContourRenderer;
import caeruleusTait.world.preview.backend.terrain.HillshadeRenderer;
import caeruleusTait.world.preview.client.WorldPreviewClient;
import caeruleusTait.world.preview.client.gui.PreviewDisplayDataProvider;
import caeruleusTait.world.preview.domain.preview.accuracy.ViewportMapping;
import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import it.unimi.dsi.fastutil.shorts.Short2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Software rasterizer and GPU-texture owner for {@link PreviewDisplay}.
 *
 * <p>Responsible for turning preview storage sections into pixels: section
 * tiling ({@link #generateRenderData}), per-mode colorization
 * ({@link #updateTexture}), hillshade/contour post-processing, structure /
 * player / spawn icon overlays, and the visible-biome/structure counting that
 * feeds the sidebar lists.
 */
class PreviewRenderEngine {

    record TextureCoordinate(int x, int z) {}

    record RenderHelper(
            PreviewSection dataSection,
            PreviewSection structureSection,
            PreviewSection.AccessData accessData,
            int sectionStartTexX,
            int sectionStartTexZ
    ) {}

    record IconData(@NotNull NativeImage img, @NotNull DynamicTexture texture) {
        public void close() {
            // Unregister before close so TextureManager does not retain the id.
            WorldPreviewClient.unregisterTexture(texture);
            texture.close();
            // NativeImage owned by PreviewContainer for structure icons
        }
    }

    private final PreviewDisplay host;

    private short[] heightFieldBuffer;
    private int[] heightFieldColors;
    // Cached hillshade state: rebuilt only when the config parameters change,
    // and the output buffer is reused across heavy renders (a full-viewport
    // render used to allocate a fresh renderer + width*height array per pass).
    private HillshadeRenderer hillshadeRenderer;
    private float hillshadeAzimuth = Float.NaN;
    private float hillshadeAltitude = Float.NaN;
    private float hillshadeAmbient = Float.NaN;
    private float hillshadeExaggeration = Float.NaN;
    private byte[] hillshadeBuffer;
    private int heightFieldWidth;
    private int heightFieldHeight;
    private Short2LongMap visibleBiomes;
    private Short2LongMap visibleStructures;
    private NativeImage previewImg;
    private DynamicTexture previewTexture;
    private NativeImage minimapImg;
    private DynamicTexture minimapTexture;
    private long[] workingVisibleBiomes;
    private long[] workingVisibleStructures;
    private int[] colorMap;
    private int[] colorMapGrayScale;
    private int[] heightColorMap;
    private int[] noiseColorMap;
    private int[] noiseColorMapTemperature;
    private int[] noiseColorMapHumidity;
    private int[] noiseColorMapContinentalness;
    private int[] noiseColorMapErosion;
    private int[] noiseColorMapDepth;
    private int[] noiseColorMapWeirdness;
    private int[] noiseColorMapPeaksAndValleys;
    // (R10) Precomputed peaks-and-valleys transform: maps the raw short noise
    // sample (indexed by rawData & 0xFFFF) directly to the final palette index.
    // Depends only on the fixed transform, not on the palette colors.
    private short[] peaksValleysLut;
    private boolean[] cavesMap;
    private IconData[] structureIcons;
    private IconData playerIcon;
    private IconData spawnIcon;
    private ItemStack[] structureItems;
    private PreviewDisplayDataProvider.StructureRenderInfo[] structureRenderInfoMap;
    private final NativeImage dummyIcon;

    // Set to true during updateTexture() whenever any workingVisibleBiomes
    // entry changes.  This lets biomesChanged() skip the O(N) array scan
    // on frames where nothing changed.
    private boolean biomeCountsDirty = false;

    PreviewRenderEngine(PreviewDisplay host) {
        this.host = host;
        this.visibleBiomes = new Short2LongOpenHashMap();
        this.visibleStructures = new Short2LongOpenHashMap();
        this.dummyIcon = new NativeImage(16, 16, true);
        this.structureIcons = new IconData[0];
    }

    // === Textures ===

    NativeImage minimapImg() { return minimapImg; }
    DynamicTexture minimapTexture() { return minimapTexture; }
    int[] colorMap() { return colorMap; }
    Short2LongMap visibleBiomes() { return visibleBiomes; }
    Short2LongMap visibleStructures() { return visibleStructures; }

    /** Closes and recreates both display textures for the given pixel size. */
    void createDisplayTextures(int texWidth, int texHeight) {
        closeDisplayTextures();
        // Create NativeImage first, then wrap in DynamicTexture
        // This ensures we have full control over the image data
        previewImg = new NativeImage(NativeImage.Format.RGBA, texWidth, texHeight, true);
        previewTexture = new DynamicTexture(() -> "preview_display", previewImg);
        // Immediately fill with opaque black and upload so the GPU texture is
        // never in an undefined state.  Without this, the render-skip path can
        // render an unuploaded texture, which appears as a black screen on
        // most GPU drivers — the root cause of the "black screen until drag" bug.
        previewImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);
        previewTexture.upload();
        minimapImg = new NativeImage(NativeImage.Format.RGBA, 80, 80, true);
        minimapTexture = new DynamicTexture(() -> "minimap_display", minimapImg);
        minimapImg.fillRect(0, 0, 80, 80, 0xFF000000);
        minimapTexture.upload();
    }

    /** Fills the main texture with opaque black and uploads it (error/loading states). */
    void fillBlackAndUpload() {
        previewImg.fillRect(0, 0, host.getTexWidth(), host.getTexHeight(), 0xFF000000);
        previewTexture.upload();
    }

    void uploadMainTexture() {
        previewTexture.upload();
    }

    DynamicTexture mainTexture() {
        return previewTexture;
    }

    /** Loads world-dependent rendering inputs (icons, colormaps, count arrays). */
    void reloadData() {
        closeIconTextures();

        // Reset the cached visible-biome / visible-structure maps so that the
        // next render frame does not mistake stale data from a previous world
        // configuration for the current one.  Without this reset,
        // biomesChanged() would see tempBiomesSet (empty, because the storage
        // was just recreated) != visibleBiomes (non-empty, left over from the
        // previous world) and call onVisibleBiomesChanged(empty), which
        // clears the biome list in the GUI.
        visibleBiomes = new Short2LongOpenHashMap();
        visibleStructures = new Short2LongOpenHashMap();

        PreviewDisplayDataProvider provider = host.dataProvider();
        structureRenderInfoMap = provider.renderStructureMap();
        structureItems = provider.structureItems();
        structureIcons = Arrays.stream(provider.structureIcons()).map(x -> new IconData(x, new DynamicTexture(() -> "struct_icon", x))).toArray(IconData[]::new);
        playerIcon = new IconData(provider.playerIcon(), new DynamicTexture(() -> "player_icon", provider.playerIcon()));
        spawnIcon = new IconData(provider.spawnIcon(), new DynamicTexture(() -> "spawn_icon", provider.spawnIcon()));
        playerIcon.texture.upload();
        spawnIcon.texture.upload();
        Arrays.stream(structureIcons).map(IconData::texture).forEach(DynamicTexture::upload);
        try {
            heightColorMap = provider.heightColorMap();
            noiseColorMap = provider.noiseColorMap();
            // Per-noise-type gradients: each noise parameter gets its own
            // dedicated color gradient for better visual differentiation.
            noiseColorMapTemperature = provider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_TEMPERATURE);
            noiseColorMapHumidity = provider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_HUMIDITY);
            noiseColorMapContinentalness = provider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_CONTINENTALNESS);
            noiseColorMapErosion = provider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_EROSION);
            noiseColorMapDepth = provider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_DEPTH);
            noiseColorMapWeirdness = provider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_WEIRDNESS);
            noiseColorMapPeaksAndValleys = provider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_PEAKS_AND_VALLEYS);
            // When per-noise-type gradients are disabled in config, fall back
            // to the shared colormap for all noise render modes.
            if (!host.config().usePerNoiseTypeGradients) {
                noiseColorMapTemperature = noiseColorMap;
                noiseColorMapHumidity = noiseColorMap;
                noiseColorMapContinentalness = noiseColorMap;
                noiseColorMapErosion = noiseColorMap;
                noiseColorMapDepth = noiseColorMap;
                noiseColorMapWeirdness = noiseColorMap;
                noiseColorMapPeaksAndValleys = noiseColorMap;
            }
            // (R10) Precompute the peaks-and-valleys transform once per reload
            // instead of calling NoiseRouterData.peaksAndValleys for every
            // pixel on every heavy render.  Replicates the per-pixel branch in
            // updateTexture() exactly (Short.MAX_VALUE scaling, 0.75 divisor,
            // clamp to [-1, 1], 512 + data*512 palette index clamped to 1023).
            // Built into a local first so a mid-build failure can never leave
            // a half-filled LUT visible to the render thread.
            final short[] pvLut = new short[65536];
            for (int i = 0; i < 65536; i++) {
                float data = (short) i / (float) Short.MAX_VALUE;
                data /= 0.75f;
                data = NoiseRouterData.peaksAndValleys(Math.min(1.0f, Math.max(-1.0f, data)));
                pvLut[i] = (short) Math.min(1023, Math.max(0, 512 + (int) (data * 512)));
            }
            peaksValleysLut = pvLut;
        } catch (Throwable e) {
            e.printStackTrace();
            // (R10) Never trust a partially built transform LUT; the noise
            // branch falls back to the per-pixel path while it is null.
            peaksValleysLut = null;
        }
        PreviewData.BiomeData[] rawBiomeMap = provider.previewData().biomeId2BiomeData();
        workingVisibleBiomes = new long[rawBiomeMap.length];
        workingVisibleStructures = new long[structureIcons.length];
        colorMap = new int[rawBiomeMap.length];
        colorMapGrayScale = new int[rawBiomeMap.length];
        cavesMap = new boolean[rawBiomeMap.length];
        for (short i = 0; i < rawBiomeMap.length; ++i) {
            colorMap[i] = textureColor(rawBiomeMap[i].color());
            colorMapGrayScale[i] = grayScale(colorMap[i]);
            cavesMap[i] = rawBiomeMap[i].isCave();
        }
    }

    private void closeIconTextures() {
        if (structureIcons != null) {
            Arrays.stream(structureIcons).forEach(IconData::close);
            structureIcons = new IconData[0];
        }
        if (playerIcon != null) {
            WorldPreviewClient.unregisterTexture(playerIcon.texture);
            playerIcon.texture.close();
            playerIcon = null;
        }
        if (spawnIcon != null) {
            WorldPreviewClient.unregisterTexture(spawnIcon.texture);
            spawnIcon.texture.close();
            spawnIcon = null;
        }
    }

    void closeDisplayTextures() {
        if (previewTexture != null) {
            WorldPreviewClient.unregisterTexture(previewTexture);
            previewTexture.close();
            previewTexture = null;
        }
        if (previewImg != null) {
            previewImg.close();
            previewImg = null;
        }
        if (minimapTexture != null) {
            WorldPreviewClient.unregisterTexture(minimapTexture);
            minimapTexture.close();
            minimapTexture = null;
        }
        if (minimapImg != null) {
            minimapImg.close();
            minimapImg = null;
        }
    }

    void close() {
        closeIconTextures();
        closeDisplayTextures();
        // Display-owned placeholder; structure NativeImages are freed by PreviewContainer
        dummyIcon.close();
    }

    /**
     * Exports the current preview image to a PNG file in the game directory.
     * @return the path to the saved file, or null on failure
     */
    String exportImage() {
        try {
            if (previewImg == null) return null;
            final java.io.File dir = new java.io.File(Minecraft.getInstance().gameDirectory, "world_preview_exports");
            dir.mkdirs();
            final String timestamp = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd_HH-mm-ss")
                    .format(java.time.LocalDateTime.now());
            final java.io.File outFile = new java.io.File(dir, "preview_" + timestamp + ".png");
            previewImg.writeToFile(outFile);
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            WorldPreview.LOGGER.error("Failed to export preview image", e);
            return null;
        }
    }

    // === Coordinate mapping ===

    private ViewportMapping currentMapping() {
        BlockPos c = host.center();
        return new ViewportMapping(
                c.getX(),
                c.getY(),
                c.getZ(),
                host.getTexWidth(),
                host.getTexHeight(),
                host.renderSettings().toScaleSpec(),
                host.minecraft().getWindow().getGuiScale()
        );
    }

    private TextureCoordinate blockToTexture(BlockPos blockPos) {
        ViewportMapping map = currentMapping();
        return new TextureCoordinate(
                map.worldToTextureX(blockPos.getX()),
                map.worldToTextureZ(blockPos.getZ())
        );
    }

    // === Rasterization ===

    List<RenderHelper> generateRenderData() {
        final BlockPos center = host.center();
        final double scaleBlockPos = host.scaleBlockPos();
        final int texWidth = host.getTexWidth();
        final int texHeight = host.getTexHeight();
        final int xMin = center.getX() - (int) (texWidth * scaleBlockPos / 2.0) - 1;
        final int zMin = center.getZ() - (int) (texHeight * scaleBlockPos / 2.0) - 1;

        final RenderSettings renderSettings = host.renderSettings();
        final int quartExpand = renderSettings.quartExpand();
        final int quartStride = renderSettings.quartStride();

        final int quartsInWidth = (texWidth / quartExpand) * quartStride;
        final int quartsInHeight = (texHeight / quartExpand) * quartStride;

        final int minQuartX = QuartPos.fromBlock(xMin);
        final int minQuartZ = QuartPos.fromBlock(zMin);

        final int maxQuartX = minQuartX + quartsInWidth;
        final int maxQuartZ = minQuartZ + quartsInHeight;

        int quartX = minQuartX;
        int quartY = QuartPos.fromBlock(center.getY());
        int quartZ = minQuartZ;

        int sectionStartTexX = 0;
        int sectionStartTexZ = 0;

        final List<RenderHelper> res = new ArrayList<>(((quartsInWidth / PreviewSection.SIZE) + 2) * ((quartsInHeight / PreviewSection.SIZE) + 2));

        PreviewStorage storage = host.workManager().previewStorage();
        if (storage == null) {
            return res;
        }

        // Load sections — no outer synchronized(storage) needed because
        // section4() already synchronizes on the correct y-layer monitor
        // (blocks[indexY]).  The outer lock was redundant and caused
        // unnecessary contention with worker threads that lock on the
        // same y-layer arrays.
        while (true) {
            long flag = renderSettings.mode.flag;
            int useY = renderSettings.mode.useY ? quartY : 0;
            PreviewSection dataSection = storage.section4(quartX, useY, quartZ, flag);
            PreviewSection structureSection = storage.section4(quartX, 0, quartZ, PreviewStorage.FLAG_STRUCT_START);
            PreviewSection.AccessData accessData = dataSection.calcQuartOffsetData(quartX, quartZ, maxQuartX, maxQuartZ);

            res.add(new RenderHelper(dataSection, structureSection, accessData, sectionStartTexX, sectionStartTexZ));

            // Can we fit more stuff in the X direction?
            if (accessData.continueX()) {
                int quartDiffX = accessData.maxX() - accessData.minX();
                quartX += quartDiffX;
                sectionStartTexX += (quartDiffX * quartExpand) / quartStride;
                continue;
            }

            // We are at the end in the X direction, can we continue in the Z direction?
            if (accessData.continueZ()) {
                int quartDiffZ = accessData.maxZ() - accessData.minZ();
                quartX = minQuartX;
                quartZ += quartDiffZ;
                sectionStartTexZ += (quartDiffZ * quartExpand) / quartStride;
                sectionStartTexX = 0;
                continue;
            }

            // We are done drawing now
            break;
        }

        return res;
    }

    /** Resets per-frame visibility counters and marks the counts dirty. */
    void beginFrameCounts() {
        Arrays.fill(workingVisibleBiomes, (short) 0);
        Arrays.fill(workingVisibleStructures, (short) 0);
        biomeCountsDirty = false;
    }

    void updateTexture(List<RenderHelper> renderData) {
        final RenderSettings renderSettings = host.renderSettings();
        final WorldPreviewConfig config = host.config();
        final int texWidth = host.getTexWidth();
        final int texHeight = host.getTexHeight();
        int texX = 0;

        final int quartExpand = renderSettings.quartExpand();
        final int quartStride = renderSettings.quartStride();

        if (renderData.isEmpty()) {
            return;
        }

        // Per-texel constants hoisted out of the sample loops below: each was
        // previously re-evaluated for every quart sample (two virtual calls
        // plus a division per pixel in the hot path).
        final int yMin = host.dataProvider().yMin();
        final short selectedBiomeId = host.selectedBiomeId();
        final boolean highlightCaves = host.highlightCaves();
        final int hfExpand = Math.max(1, quartExpand);

        boolean needHeightField = renderSettings.mode == RenderSettings.RenderMode.HEIGHTMAP
                && (config.enableHillshade || config.enableContours);
        if (needHeightField) {
            heightFieldWidth = texWidth / hfExpand;
            heightFieldHeight = texHeight / hfExpand;
            int bufSize = heightFieldWidth * heightFieldHeight;
            if (heightFieldBuffer == null || heightFieldBuffer.length < bufSize) {
                heightFieldBuffer = new short[bufSize];
                heightFieldColors = new int[bufSize];
            }
            java.util.Arrays.fill(heightFieldBuffer, (short) 0);
            java.util.Arrays.fill(heightFieldColors, 0x00000000);
        } else {
            heightFieldBuffer = null;
            heightFieldColors = null;
        }

        // The seven NOISE_* modes share one formula and differ only in palette.
        // Resolve the palette once per frame instead of duplicating the branch.
        final int[] noisePalette = switch (renderSettings.mode) {
            case NOISE_TEMPERATURE -> noiseColorMapTemperature;
            case NOISE_HUMIDITY -> noiseColorMapHumidity;
            case NOISE_CONTINENTALNESS -> noiseColorMapContinentalness;
            case NOISE_EROSION -> noiseColorMapErosion;
            case NOISE_DEPTH -> noiseColorMapDepth;
            case NOISE_WEIRDNESS -> noiseColorMapWeirdness;
            case NOISE_PEAKS_AND_VALLEYS -> noiseColorMapPeaksAndValleys;
            default -> null;
        };

        // Render the biomes / heightmap
        //
        // Batch optimization: instead of calling fillRect once per quart sample
        // (which is one JNI call per pixel when quartExpand==1), we detect runs
        // of consecutive same-color pixels along the Z axis and merge them into
        // a single fillRect call.  This reduces JNI calls by 3-10x on typical
        // biome maps where large regions share the same biome.
        for (RenderHelper r : renderData) {
            texX = r.sectionStartTexX();

            for (int x = r.accessData().minX(); x < r.accessData().maxX(); x += quartStride) {
                int texZ = r.sectionStartTexZ();

                // Batch state for consecutive same-color pixels along Z
                int batchStartZ = texZ;
                int batchEndZ = texZ;
                int batchColor = 0xFF000000;
                boolean batchActive = false;

                for (int z = r.accessData().minZ(); z < r.accessData().maxZ(); z += quartStride) {
                    // Read the biome data
                    short rawData = r.dataSection().get(x, z);
                    int color = 0xFF000000;
                    switch (renderSettings.mode) {
                        case BIOMES -> {
                            if (rawData >= 0 && rawData < colorMap.length) {
                                color = selectedBiomeId >= 0 || highlightCaves
                                        ? colorMapGrayScale[rawData] : colorMap[rawData];
                                if (selectedBiomeId == rawData || (highlightCaves && cavesMap[rawData])) {
                                    color = colorMap[rawData];
                                }
                                workingVisibleBiomes[rawData] += 1;
                                biomeCountsDirty = true;
                            }
                        }
                        case HEIGHTMAP -> {
                            if (rawData > Short.MIN_VALUE) {
                                int idx = rawData - yMin;
                                if (idx >= 0 && idx < heightColorMap.length) {
                                    color = heightColorMap[idx];
                                }
                                if (heightFieldBuffer != null) {
                                    int hfIdx = (texZ / hfExpand) * heightFieldWidth + texX / hfExpand;
                                    if (hfIdx >= 0 && hfIdx < heightFieldBuffer.length) {
                                        // 1 unit = 1 world block above yMin; a byte clamp here used to
                                        // flatten every sample above yMin+191 (overworld surface y>=126)
                                        int hfHeight = Math.max(0, idx);
                                        heightFieldBuffer[hfIdx] = (short) hfHeight;
                                        heightFieldColors[hfIdx] = color;
                                    }
                                }
                            }
                        }
                        case INTERSECTIONS -> {
                            if (rawData >= 0) {
                                // Main y-intersection
                                color = MapColor.byId(rawData).col;
                                color = textureColor(color == 0 ? 0xFFFFFF : color);
                            } else if (rawData > Short.MIN_VALUE) {
                                // See through one layer of air
                                color = MapColor.byId(-rawData).col;
                                color = highlightColor(textureColor(color == 0 ? 0xFFFFFF : color));
                            }
                        }
                        case NOISE_TEMPERATURE, NOISE_HUMIDITY, NOISE_CONTINENTALNESS, NOISE_EROSION,
                             NOISE_DEPTH, NOISE_WEIRDNESS, NOISE_PEAKS_AND_VALLEYS -> {
                            if (rawData > Short.MIN_VALUE && noisePalette != null) {
                                if (renderSettings.mode == RenderSettings.RenderMode.NOISE_PEAKS_AND_VALLEYS
                                        && peaksValleysLut != null) {
                                    // (R10) Precomputed transform: raw short sample
                                    // -> final palette index in one lookup.
                                    color = noisePalette[peaksValleysLut[rawData & 0xFFFF]];
                                } else {
                                    float data = ((float) rawData) / ((float) Short.MAX_VALUE);
                                    if (renderSettings.mode == RenderSettings.RenderMode.NOISE_PEAKS_AND_VALLEYS) {
                                        data /= 0.75f;
                                        data = NoiseRouterData.peaksAndValleys(Math.min(1.0f, Math.max(-1.0f, data)));
                                    }
                                    final int idx = Math.min(1023, Math.max(0, 512 + (int) (data * 512)));
                                    color = noisePalette[idx];
                                }
                            }
                        }
                        default -> {
                        }
                    }

                    int w = Math.min(texWidth - texX, quartExpand);
                    int h = Math.min(texHeight - texZ, quartExpand);

                    // Extend current batch if same color and same pixel size
                    if (batchActive && color == batchColor && w == quartExpand && h == quartExpand) {
                        batchEndZ += quartExpand;
                    } else {
                        // Flush previous batch
                        if (batchActive && batchEndZ > batchStartZ) {
                            int batchH = Math.min(texHeight - batchStartZ, batchEndZ - batchStartZ);
                            previewImg.fillRect(texX, batchStartZ, quartExpand, batchH, batchColor);
                        }
                        // Start new batch
                        batchStartZ = texZ;
                        batchColor = color;
                        batchEndZ = texZ + h;
                        batchActive = true;
                    }

                    texZ += quartExpand;
                }
                // Flush remaining batch
                if (batchActive && batchEndZ > batchStartZ) {
                    int batchH = Math.min(texHeight - batchStartZ, batchEndZ - batchStartZ);
                    previewImg.fillRect(texX, batchStartZ, quartExpand, batchH, batchColor);
                }

                texX += quartExpand;
            }
        }
        if (heightFieldBuffer != null && heightFieldWidth > 0 && heightFieldHeight > 0) {
            applyHeightmapPostProcessing(quartExpand, texWidth, texHeight);
        }

    }

    private void applyHeightmapPostProcessing(int quartExpand, int texWidth, int texHeight) {
        final WorldPreviewConfig config = host.config();
        int fw = heightFieldWidth;
        int fh = heightFieldHeight;
        if (fw < 2 || fh < 2) return;

        byte[] shadeBuffer = null;
        if (config.enableHillshade) {
            if (hillshadeRenderer == null
                    || hillshadeAzimuth != config.hillshadeAzimuth
                    || hillshadeAltitude != config.hillshadeAltitude
                    || hillshadeAmbient != config.hillshadeAmbient
                    || hillshadeExaggeration != config.hillshadeExaggeration) {
                hillshadeRenderer = new HillshadeRenderer(
                        config.hillshadeAzimuth, config.hillshadeAltitude,
                        config.hillshadeAmbient, config.hillshadeExaggeration, 0.5f);
                hillshadeAzimuth = config.hillshadeAzimuth;
                hillshadeAltitude = config.hillshadeAltitude;
                hillshadeAmbient = config.hillshadeAmbient;
                hillshadeExaggeration = config.hillshadeExaggeration;
            }
            final int size = fw * fh;
            if (hillshadeBuffer == null || hillshadeBuffer.length < size) {
                hillshadeBuffer = new byte[size];
            }
            // Write the returned buffer back: render() only reuses the caller's
            // array when it is large enough, otherwise it allocates a fresh
            // one, so the field must track the array actually in use.
            hillshadeBuffer = hillshadeRenderer.render(heightFieldBuffer, fw, fh, (float) quartExpand, hillshadeBuffer);
            shadeBuffer = hillshadeBuffer;
        }

        if (shadeBuffer != null) {
            for (int fy = 0; fy < fh; fy++) {
                for (int fx = 0; fx < fw; fx++) {
                    int idx = fy * fw + fx;
                    int baseColor = heightFieldColors[idx];
                    if (baseColor == 0x00000000) continue;
                    int shade = shadeBuffer[idx] & 0xFF;
                    heightFieldColors[idx] = HillshadeRenderer.applyShade(baseColor, (byte) shade);
                }
            }
        }

        if (config.enableContours) {
            ContourRenderer contourRenderer = new ContourRenderer(
                    config.contourInterval, config.contourMinorLines,
                    0xC08B4513, 0x608B6914);
            contourRenderer.render(heightFieldBuffer, heightFieldColors, fw, fh);
        }

        for (int fy = 0; fy < fh; fy++) {
            for (int fx = 0; fx < fw; fx++) {
                int color = heightFieldColors[fy * fw + fx];
                if (color == 0x00000000) continue;
                int px = fx * quartExpand;
                int py = fy * quartExpand;
                int w = Math.min(quartExpand, texWidth - px);
                int h = Math.min(quartExpand, texHeight - py);
                if (w > 0 && h > 0) {
                    previewImg.fillRect(px, py, w, h, color);
                }
            }
        }
    }

    // === Overlays ===

    void renderStructures(List<RenderHelper> renderData, GuiGraphicsExtractor GuiGraphicsExtractor) {
        renderStructures(renderData, GuiGraphicsExtractor, true);
    }

    /**
     * @param updateHoverGrid false on render-skip frames: the hover grid from
     *     the last heavy render is still valid, and re-adding the same
     *     structures every idle frame made the grid grow without bound.
     */
    void renderStructures(List<RenderHelper> renderData, GuiGraphicsExtractor GuiGraphicsExtractor, boolean updateHoverGrid) {
        // Render-skip frames pass the cached list, which is null until the
        // first heavy render (and after invalidateRenderCache): nothing to draw.
        if (!host.config().sampleStructures || renderData == null) {
            return;
        }
        final RenderSettings renderSettings = host.renderSettings();
        final int texWidth = host.getTexWidth();
        final int texHeight = host.getTexHeight();

        // (R3) One viewport mapping for the whole pass: blockToTexture() used
        // to build a fresh ViewportMapping (+ ScaleSpec) per structure, and the
        // screen conversion below built yet another one per structure.
        final ViewportMapping map = currentMapping();

        // Draw structures
        //  - Do this in a separate RenderHelper loop to ensure that the biome data is overwritten
        for (RenderHelper r : renderData) {
            for (PreviewSection.PreviewStruct structure : r.structureSection().structures()) {
                short id = structure.structureId();
                // Guard against invalid structure IDs from stale storage data
                if (id < 0 || id >= structureIcons.length || id >= structureItems.length || id >= structureRenderInfoMap.length) {
                    continue;
                }
                // (R3) Inlined blockToTexture(structure.center()) on the hoisted
                // mapping — identical world->texel math, no per-structure mapping.
                final TextureCoordinate texCenter = new TextureCoordinate(
                        map.worldToTextureX(structure.center().getX()),
                        map.worldToTextureZ(structure.center().getZ())
                );
                IconData iconData = structureIcons[id];
                NativeImage icon = iconData.img;
                DynamicTexture iconTexture = iconData.texture;
                ItemStack item = structureItems[id];
                if (icon == null && item == null) {
                    continue;
                }
                if (icon == null) {
                    icon = dummyIcon;
                }

                // Check if visible
                final int xMin = -(icon.getWidth() / 2);
                final int xMax = (icon.getWidth() / 2) + 1 + texWidth;
                final int zMin = -(icon.getHeight() / 2);
                final int zMax = (icon.getHeight() / 2) + 1 + texHeight;
                if (texCenter.x() < xMin || texCenter.z() < zMin || texCenter.x() > xMax || texCenter.z() > zMax) {
                    continue;
                }

                if (id >= 0 && id < workingVisibleStructures.length) {
                    workingVisibleStructures[id] += 1;
                }

                // Do not render hidden structures, but still count them
                if (!structureRenderInfoMap[id].show() || renderSettings.hideAllStructures) {
                    continue;
                }

                // Render icon / item
                // texCenter/texStart are in texture-pixel space; convert via ViewportMapping
                final int texStartX = texCenter.x() - (icon.getWidth() / 2);
                final int texStartZ = texCenter.z() - (icon.getHeight() / 2);

                // (R3) Reuses the mapping hoisted at the head of this method.
                final int rXMin = map.textureToScreenX(texStartX, host.getX());
                final int rZMin = map.textureToScreenZ(texStartZ, host.getY());
                final int rXMax = map.textureToScreenXCeil(texStartX + icon.getWidth(), host.getX());
                final int rZMax = map.textureToScreenZCeil(texStartZ + icon.getHeight(), host.getY());

                if (item != null) {
                    // Items are always 16 GUI pixels; center on texCenter in GUI space
                    final int itemX = map.textureToScreenXRound(texCenter.x(), host.getX()) - 8;
                    final int itemZ = map.textureToScreenZRound(texCenter.z(), host.getY()) - 8;
                    GuiGraphicsExtractor.item(item, itemX, itemZ);
                } else if (iconTexture != null) {
                    WorldPreviewClient.renderTexture(GuiGraphicsExtractor, iconTexture, rXMin, rZMin, rXMax, rZMax);
                }

                if (updateHoverGrid) {
                    host.hoverInspector.putStructEntry(
                            texCenter.x(),
                            texCenter.z(),
                            icon.getWidth(),
                            icon.getHeight(),
                            structure
                    );
                }
            }
        }
    }

    void renderSpawnPin(GuiGraphicsExtractor GuiGraphicsExtractor) {
        BlockPos pinPos = host.spawnPinPos();
        if (pinPos == null) return;
        renderStickyIcon(GuiGraphicsExtractor, playerIcon, pinPos);
    }

    void renderPlayerAndSpawn(GuiGraphicsExtractor GuiGraphicsExtractor) {
        if (!host.config().showPlayer) {
            return;
        }

        PreviewDisplayDataProvider.PlayerData playerData =
                host.dataProvider().getPlayerData(host.minecraft().getUser().getProfileId());
        if (playerData.currentPos() != null) {
            renderStickyIcon(GuiGraphicsExtractor, playerIcon, playerData.currentPos());
        }
        if (playerData.spawnPos() != null) {
            renderStickyIcon(GuiGraphicsExtractor, spawnIcon, playerData.spawnPos());
        }
    }

    /**
     * Render the player and spawn icons in double the size
     */
    private void renderStickyIcon(GuiGraphicsExtractor GuiGraphicsExtractor, IconData iconData, BlockPos pos) {
        final Minecraft minecraft = host.minecraft();
        final double guiScale = minecraft.getWindow().getGuiScale();
        final NativeImage icon = iconData.img;
        final int texWidth = host.getTexWidth();
        final int texHeight = host.getTexHeight();

        // (R3) Inlined blockToTexture(pos) on a single hoisted mapping instead
        // of allocating one inside blockToTexture; same clamping as before.
        final ViewportMapping map = currentMapping();
        final TextureCoordinate texCenter = new TextureCoordinate(
                Math.max(0, Math.min(texWidth, map.worldToTextureX(pos.getX()))),
                Math.max(0, Math.min(texHeight, map.worldToTextureZ(pos.getZ())))
        );

        // Render icon at double size — tex coords are texture-pixel space; convert to GUI
        final int texStartX = texCenter.x() - icon.getWidth();
        final int texStartZ = texCenter.z() - icon.getHeight();

        final int rXMin = host.getX() + (int) Math.floor(texStartX / guiScale);
        final int rZMin = host.getY() + (int) Math.floor(texStartZ / guiScale);
        final int rXMax = host.getX() + (int) Math.ceil((texStartX + icon.getWidth() * 2) / guiScale);
        final int rZMax = host.getY() + (int) Math.ceil((texStartZ + icon.getHeight() * 2) / guiScale);

        WorldPreviewClient.renderTexture(GuiGraphicsExtractor, iconData.texture, rXMin, rZMin, rXMax, rZMax);
    }

    // === Visible-count reporting ===

    /**
     * Pushes updated visible biome/structure counts to the sidebar lists.
     * Fast path: if updateTexture didn't modify any biome counts this
     * frame, skip the O(N) array scan entirely.
     */
    void biomesChanged() {
        if (!biomeCountsDirty) {
            return;
        }

        // Quick check: if nothing changed since last frame, skip the expensive
        // map creation and comparison entirely.  We compare the raw arrays
        // directly because they are always the same length and a simple
        // reference-equality / content check is far cheaper than building
        // new Short2LongOpenHashMap objects every frame.
        boolean biomesChanged = false;
        boolean structuresChanged = false;

        // Only build new maps if the working arrays actually differ from what
        // we last reported.  This avoids creating Short2LongOpenHashMap
        // objects (and their internal hash tables) every single frame when the
        // user is not scrolling.
        if (visibleBiomes.size() == 0) {
            // Need to check if any biomes are now visible
            for (short i = 0; i < workingVisibleBiomes.length; ++i) {
                if (workingVisibleBiomes[i] > 0) { biomesChanged = true; break; }
            }
        } else {
            // Quick size check first, then content check
            int visibleCount = 0;
            for (short i = 0; i < workingVisibleBiomes.length; ++i) {
                if (workingVisibleBiomes[i] > 0) visibleCount++;
            }
            biomesChanged = (visibleCount != visibleBiomes.size());
            if (!biomesChanged) {
                // Same count — verify values match
                for (short i = 0; i < workingVisibleBiomes.length; ++i) {
                    if (workingVisibleBiomes[i] > 0) {
                        long prev = visibleBiomes.get(i);
                        if (prev != workingVisibleBiomes[i]) {
                            biomesChanged = true;
                            break;
                        }
                    }
                }
            }
        }

        if (visibleStructures.size() == 0) {
            for (short i = 0; i < workingVisibleStructures.length; ++i) {
                if (workingVisibleStructures[i] > 0) { structuresChanged = true; break; }
            }
        } else {
            int visibleCount = 0;
            for (short i = 0; i < workingVisibleStructures.length; ++i) {
                if (workingVisibleStructures[i] > 0) visibleCount++;
            }
            structuresChanged = (visibleCount != visibleStructures.size());
            if (!structuresChanged) {
                for (short i = 0; i < workingVisibleStructures.length; ++i) {
                    if (workingVisibleStructures[i] > 0) {
                        long prev = visibleStructures.get(i);
                        if (prev != workingVisibleStructures[i]) {
                            structuresChanged = true;
                            break;
                        }
                    }
                }
            }
        }

        if (biomesChanged) {
            Short2LongMap tempBiomesSet = new Short2LongOpenHashMap(workingVisibleBiomes.length);
            for (short i = 0; i < workingVisibleBiomes.length; ++i) {
                if (workingVisibleBiomes[i] > 0) {
                    tempBiomesSet.put(i, workingVisibleBiomes[i]);
                }
            }
            host.dataProvider().onVisibleBiomesChanged(tempBiomesSet);
            visibleBiomes = tempBiomesSet;
        }

        if (structuresChanged) {
            Short2LongMap tempStructuresSet = new Short2LongOpenHashMap(workingVisibleStructures.length);
            for (short i = 0; i < workingVisibleStructures.length; ++i) {
                if (workingVisibleStructures[i] > 0) {
                    tempStructuresSet.put(i, workingVisibleStructures[i]);
                }
            }
            host.dataProvider().onVisibleStructuresChanged(tempStructuresSet);
            visibleStructures = tempStructuresSet;
        }
    }

    // === Color helpers ===

    private static int textureColor(int orig) {
        // MC 1.21.6+: NativeImage stores pixels in ARGB format (not ABGR as in 1.21).
        // Only add full alpha; do NOT swap R and B channels.
        return orig | (0xFF << 24);
    }

    private static int highlightColor(int orig) {
        int R = (orig >> 0) & 0xFF;
        int G = (orig >> 8) & 0xFF;
        int B = (orig >> 16) & 0xFF;

        final int diff = ((R + G + B) / 3) > 200 ? -100 : 100;

        R += diff;
        G += diff;
        B += diff;
        R = Math.max(Math.min(R, 255), 0);
        G = Math.max(Math.min(G, 255), 0);
        B = Math.max(Math.min(B, 255), 0);
        return (0xFF << 24) | (B << 16) | (G << 8) | R;
    }

    private static int grayScale(int orig) {
        int R = (orig >> 0) & 0xFF;
        int G = (orig >> 8) & 0xFF;
        int B = (orig >> 16) & 0xFF;

        final int gray = Math.max(32, Math.min(256 - 32, (R + G + B) / 3));
        return (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
    }
}
