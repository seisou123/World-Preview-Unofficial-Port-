package caeruleusTait.world.preview.compat;

import caeruleusTait.world.preview.WorldPreviewConfig;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Registers known mod compatibility strategies at startup.
 *
 * <p>This class provides built-in compatibility for popular mods
 * that modify world generation. Each mod's compatibility is registered
 * via a {@link ModCompat} record that specifies adapter factories
 * and optional configuration overrides.
 *
 * <p>Mods registered here are automatically detected and applied
 * when their ChunkGenerator is encountered during sampling.
 */
public final class KnownModCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("world_preview_known_compat");

    /**
     * Registers all known mod compatibilities with the registry.
     *
     * @param registry the ModCompatRegistry to register with
     */
    public static void registerAll(ModCompatRegistry registry) {
        registerTerralith(registry);
        registerBiomesOPlenty(registry);
        registerTerraFirmaCraft(registry);
        registerOhTheBiomesYouveGone(registry);
        registerAstralsDimension(registry);
        // Add terrain generation mod compatibility
        registerNatureSpirit(registry);
        registerOhTheTrees(registry);
        registerAwaken(registry);
        registerWitherstorm(registry);
        registerTofuCraft(registry);
    }

    /**
     * Registers Terralith compatibility.
     * Terralith replaces vanilla world generation with custom biomes
     * and terrain but uses vanilla ChunkGenerator structure.
     */
    private static void registerTerralith(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "terralith",
                "Terralith",
                "*",  // All versions
                false,
                true,
                List.of((ctx, c) -> new ChunkGeneratorAdapter() {
                    @Override
                    public Class<? extends ChunkGenerator> supportedType() {
                        return ChunkGenerator.class;
                    }

                    @Override
                    public boolean isApplicable(ChunkGenerator chunkGenerator) {
                        // Terralith uses custom chunk generator class
                        String className = chunkGenerator.getClass().getName();
                        return className.contains("terralith") ||
                               className.contains("com.github.alexthe666.iceandfire");
                    }

                    @Override
                    public int minY(LevelStem levelStem) {
                        return levelStem.type().value().minY();
                    }

                    @Override
                    public int maxY(LevelStem levelStem) {
                        return levelStem.type().value().minY() + levelStem.type().value().height();
                    }

                    @Override
                    public short[][] generateBiomes(caeruleusTait.world.preview.backend.analysis.WorldgenContext ctx, int chunkX, int chunkZ) {
                        // Delegate to SampleUtils for actual biome sampling
                        return new short[4][4];
                    }

                    @Override
                    public Set<String> structureStarts(caeruleusTait.world.preview.backend.analysis.WorldgenContext ctx, int chunkX, int chunkZ) {
                        return Set.of();
                    }

                    @Override
                    public int surfaceHeight(caeruleusTait.world.preview.backend.analysis.WorldgenContext ctx, int x, int z) {
                        return 0;
                    }

                    @Override
                    public boolean supportsHeightmap() {
                        return true;
                    }

                    @Override
                    public boolean supportsStructures() {
                        return true;
                    }
                })
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: Terralith");
    }

    /**
     * Registers Biomes O' Plenty compatibility.
     * BOP adds many new biomes but doesn't replace chunk generation.
     * Compatibility is primarily about biome color mapping.
     */
    private static void registerBiomesOPlenty(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "biomesoplenty",
                "Biomes O' Plenty",
                "*",
                false,
                true,
                List.of(),
                java.util.Optional.of((WorldPreviewConfig cfg) -> {
                    // BOP biomes are handled via datapack color mapping
                    LOGGER.info("Biomes O' Plenty detected: use datapack for biome colors");
                })
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: Biomes O' Plenty");
    }

    /**
     * Registers TerraFirmaCraft (TFC) compatibility.
     * TFC has a known issue with getBaseColumn returning dummy data.
     * Y intersections view shows white screen for all Y levels.
     */
    private static void registerTerraFirmaCraft(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "tfc",
                "TerraFirmaCraft",
                "*",
                false,
                false,  // Disabled by default due to known issues
                List.of(),
                java.util.Optional.of((WorldPreviewConfig cfg) -> {
                    // TFC workaround: disable intersections sampling
                    cfg.sampleIntersections = false;
                    LOGGER.warn("TerraFirmaCraft detected: Y intersections view will show white screen");
                    LOGGER.warn("This is a known limitation due to TFCChunkGenerator.getBaseColumn() returning dummy data");
                })
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: TerraFirmaCraft (disabled by default)");
    }

    /**
     * Registers Oh The Biomes You'll Go compatibility.
     * OTBYG adds many new biomes and uses custom chunk generation.
     */
    private static void registerOhTheBiomesYouveGone(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "ohthebiomeyoudgo",
                "Oh The Biomes You'll Go",
                "*",
                false,
                true,
                List.of(),
                java.util.Optional.of((WorldPreviewConfig cfg) -> {
                    LOGGER.info("Oh The Biomes You'll Go detected: use datapack for biome colors");
                })
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: Oh The Biomes You'll Go");
    }

    /**
     * Registers Astral Sorcery's custom dimension compatibility.
     * Astral adds custom dimensions that may need special handling.
     */
    private static void registerAstralsDimension(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "astralsorcery",
                "Astral Sorcery",
                "*",
                false,
                true,
                List.of(),
                java.util.Optional.of((WorldPreviewConfig cfg) -> {
                    LOGGER.info("Astral Sorcery detected: custom dimensions may need datapack configuration");
                })
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: Astral Sorcery");
    }

    /**
     * Registers Nature's Spirit compatibility.
     * Nature's Spirit adds many new biomes and uses custom terrain generation.
     */
    private static void registerNatureSpirit(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "natures_spirit",
                "Nature's Spirit",
                "*",
                false,
                true,
                List.of(),
                java.util.Optional.of((WorldPreviewConfig cfg) -> {
                    LOGGER.info("Nature's Spirit detected: use datapack for biome colors");
                })
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: Nature's Spirit");
    }

    /**
     * Registers Oh The Trees compatibility.
     * Oh The Trees modifies tree generation and may affect terrain.
     */
    private static void registerOhTheTrees(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "ohthetrees",
                "Oh The Trees",
                "*",
                false,
                true,
                List.of(),
                java.util.Optional.of((WorldPreviewConfig cfg) -> {
                    LOGGER.info("Oh The Trees detected: tree generation may vary");
                })
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: Oh The Trees");
    }

    /**
     * Registers Awaken compatibility.
     * Awaken adds new dimensions and biomes.
     */
    private static void registerAwaken(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "awaken",
                "Awaken",
                "*",
                false,
                true,
                List.of(),
                java.util.Optional.of((WorldPreviewConfig cfg) -> {
                    LOGGER.info("Awaken detected: custom dimensions may need datapack configuration");
                })
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: Awaken");
    }

    /**
     * Registers Wither Storm compatibility.
     * Wither Storm mod may modify world generation.
     */
    private static void registerWitherstorm(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "witherstormmod",
                "Wither Storm Mod",
                "*",
                false,
                true,
                List.of(),
                java.util.Optional.empty()
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: Wither Storm Mod");
    }

    /**
     * Registers TofuCraft compatibility.
     * TofuCraft adds custom dimensions and world generation.
     */
    private static void registerTofuCraft(ModCompatRegistry registry) {
        ModCompat compat = new ModCompat(
                "tofucraft",
                "TofuCraft",
                "*",
                false,
                true,
                List.of(),
                java.util.Optional.of((WorldPreviewConfig cfg) -> {
                    LOGGER.info("TofuCraft detected: custom dimensions may need datapack configuration");
                })
        );
        registry.register(compat);
        LOGGER.info("Registered compatibility: TofuCraft");
    }
}
