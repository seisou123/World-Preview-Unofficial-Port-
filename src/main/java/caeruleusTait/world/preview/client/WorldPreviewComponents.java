// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client;

import net.minecraft.network.chat.Component;

public class WorldPreviewComponents {
    // Main view components
    public static final Component TITLE = Component.translatable("world_preview.preview.title");
    public static final Component TITLE_FULL = Component.translatable("world_preview.preview.title-full");
    public static final Component SAVING_PREVIEW = Component.translatable("world_preview.preview.saving");
    public static final Component LOADING_PREVIEW = Component.translatable("world_preview.preview.loading");
    public static final Component SEED_FIELD = Component.translatable("world_preview.preview.seed-field");
    public static final Component SEED_LABEL = Component.translatable("world_preview.preview.seed-label");
    public static final Component BTN_RANDOM = Component.translatable("world_preview.preview.btn-random");
    public static final Component BTN_SAVE_SEED = Component.translatable("world_preview.preview.btn-save-seed");
    public static final Component BTN_SETTINGS = Component.translatable("world_preview.preview.btn-settings");
    public static final Component BTN_CAVES = Component.translatable("world_preview.preview.btn-caves");
    public static final Component BTN_HOME = Component.translatable("world_preview.preview.btn-home");
    public static final Component BTN_SWITCH_STRUCT_DISABLED = Component.translatable("world_preview.preview.btn-cycle.structures.disabled.tooltip");
    public static final Component BTN_TOGGLE_STRUCTURES = Component.translatable("world_preview.preview.btn-toggle-structures");
    public static final Component BTN_TOGGLE_STRUCTURES_DISABLED = Component.translatable("world_preview.preview.btn-toggle-structures.disabled");
    public static final Component BTN_RESET_STRUCTURES = Component.translatable("world_preview.preview.btn-reset-structures");
    public static final Component BTN_RESET_STRUCTURES_TOOLTIP = Component.translatable("world_preview.preview.btn-reset-structures.tooltip");
    public static final Component BTN_TOGGLE_BIOMES = Component.translatable("world_preview.preview.btn-toggle-biomes");
    public static final Component BTN_TOGGLE_NOISE = Component.translatable("world_preview.preview.btn-toggle-noise");
    public static final Component BTN_TOGGLE_NOISE_DISABLED = Component.translatable("world_preview.preview.btn-toggle-noise.disabled");
    public static final Component BTN_TOGGLE_HEIGHTMAP = Component.translatable("world_preview.preview.btn-toggle-heightmap");
    public static final Component BTN_TOGGLE_HEIGHTMAP_DISABLED = Component.translatable("world_preview.preview.btn-toggle-heightmap.disabled");
    public static final Component BTN_TOGGLE_INTERSECT = Component.translatable("world_preview.preview.btn-toggle-intersect");
    public static final Component BTN_TOGGLE_INTERSECT_DISABLED = Component.translatable("world_preview.preview.btn-toggle-intersect.disabled");
    public static final Component BTN_TOGGLE_EXPAND =  Component.translatable("world_preview.preview.btn-toggle-expand");
    public static final Component BTN_CYCLE_NOISE =  Component.translatable("world_preview.preview.btn-cycle-noise");
    public static final Component BTN_EXPORT_IMAGE = Component.translatable("world_preview.preview.export_image");
    public static final Component BTN_EXPORT_IMAGE_TOOLTIP = Component.translatable("world_preview.preview.export_image.tooltip");
    public static final Component BTN_SET_SPAWN = Component.translatable("world_preview.preview.btn-set-spawn");
    public static final Component BTN_SET_SPAWN_TOOLTIP = Component.translatable("world_preview.preview.btn-set-spawn.tooltip");
    public static final Component BTN_SET_SPAWN_PLACED = Component.translatable("world_preview.preview.btn-set-spawn.placed");
    public static final Component ANALYSIS_TITLE = Component.translatable("world_preview.analysis.title");
    public static final Component ANALYSIS_OPEN = Component.translatable("world_preview.analysis.open");
    public static final Component ANALYSIS_START = Component.translatable("world_preview.analysis.start");
    public static final Component ANALYSIS_CANCEL = Component.translatable("world_preview.analysis.cancel");

    // === Seed search ===
    public static final Component SEARCH_FIND_BIOME = Component.translatable("world_preview.search.find_biome");
    public static final Component SEARCH_STOP = Component.translatable("world_preview.search.stop");
    public static final Component SEARCH_PROGRESS = Component.translatable("world_preview.search.progress");
    public static final Component SEARCH_FOUND = Component.translatable("world_preview.search.found");
    public static final Component SEARCH_NOT_FOUND = Component.translatable("world_preview.search.not_found");
    public static final Component SEARCH_STOPPED = Component.translatable("world_preview.search.stopped");
    public static final Component SEARCH_ERROR = Component.translatable("world_preview.search.error");

    // === Advanced seed search screen (v1.5) ===
    public static final Component SEARCH_TITLE = Component.translatable("world_preview.search.title");
    public static final Component SEARCH_OPEN = Component.translatable("world_preview.search.open");
    public static final Component SEARCH_OPEN_TOOLTIP = Component.translatable("world_preview.search.open.tooltip");
    public static final Component SEARCH_BIOME = Component.translatable("world_preview.search.biome");
    public static final Component SEARCH_BIOME_NONE = Component.translatable("world_preview.search.biome.none");
    public static final Component SEARCH_CLEAR_BIOME = Component.translatable("world_preview.search.biome.clear");
    public static final Component SEARCH_STRUCTURE = Component.translatable("world_preview.search.structure");
    public static final Component SEARCH_STRUCTURE_NONE = Component.translatable("world_preview.search.structure.none");
    public static final Component SEARCH_ANCHOR = Component.translatable("world_preview.search.anchor");
    public static final Component SEARCH_ANCHOR_CENTER = Component.translatable("world_preview.search.anchor.center");
    public static final Component SEARCH_ANCHOR_ORIGIN = Component.translatable("world_preview.search.anchor.origin");
    public static final Component SEARCH_MIN_AREA = Component.translatable("world_preview.search.min_area");
    public static final Component SEARCH_STRUCTURE_DISTANCE = Component.translatable("world_preview.search.structure_distance");
    public static final Component SEARCH_ATTEMPTS = Component.translatable("world_preview.search.attempts");
    public static final Component SEARCH_HITS = Component.translatable("world_preview.search.hits");
    public static final Component SEARCH_START = Component.translatable("world_preview.search.start");
    public static final Component SEARCH_VIEW = Component.translatable("world_preview.search.view");
    public static final Component SEARCH_VIEW_RESULTS = Component.translatable("world_preview.search.view.results");
    public static final Component SEARCH_VIEW_HISTORY = Component.translatable("world_preview.search.view.history");
    public static final Component SEARCH_VIEW_FAVORITES = Component.translatable("world_preview.search.view.favorites");
    public static final Component SEARCH_NO_CRITERIA = Component.translatable("world_preview.search.no_criteria");
    public static final Component SEARCH_APPLIED = Component.translatable("world_preview.search.applied");
    public static final Component SEARCH_APPLIED_CLIPBOARD = Component.translatable("world_preview.search.applied.clipboard");

    // === Terrain map export ===
    public static final Component TERRAIN_EXPORT_TITLE = Component.translatable("world_preview.terrain_export.title");
    public static final Component TERRAIN_EXPORT_OPEN = Component.translatable("world_preview.terrain_export.open");
    public static final Component TERRAIN_EXPORT_OPEN_TOOLTIP = Component.translatable("world_preview.terrain_export.open.tooltip");
    public static final Component TERRAIN_EXPORT_RADIUS = Component.translatable("world_preview.terrain_export.radius");
    public static final Component TERRAIN_EXPORT_RESOLUTION = Component.translatable("world_preview.terrain_export.resolution");
    public static final Component TERRAIN_EXPORT_START = Component.translatable("world_preview.terrain_export.start");
    public static final Component TERRAIN_EXPORT_CANCEL = Component.translatable("world_preview.terrain_export.cancel");
    public static final Component TERRAIN_EXPORT_LEGEND = Component.translatable("world_preview.terrain_export.legend");
    public static final Component TERRAIN_EXPORT_IDLE = Component.translatable("world_preview.terrain_export.idle");
    public static final Component TERRAIN_EXPORT_COMPLETE = Component.translatable("world_preview.terrain_export.complete");
    public static final Component TERRAIN_EXPORT_CANCELLED = Component.translatable("world_preview.terrain_export.cancelled");

    // === Hillshade ===
    public static final Component SETTINGS_HILLSHADE = Component.translatable("world_preview.settings.general.hillshade");
    public static final Component SETTINGS_HILLSHADE_TOOLTIP = Component.translatable("world_preview.settings.general.hillshade.tooltip");

    // === Per-noise-type gradients ===
    public static final Component SETTINGS_NOISE_GRADIENTS = Component.translatable("world_preview.settings.general.noise_gradients");
    public static final Component SETTINGS_NOISE_GRADIENTS_TOOLTIP = Component.translatable("world_preview.settings.general.noise_gradients.tooltip");

    // === Contour lines ===
    public static final Component SETTINGS_CONTOURS = Component.translatable("world_preview.settings.general.contours");
    public static final Component SETTINGS_CONTOURS_TOOLTIP = Component.translatable("world_preview.settings.general.contours.tooltip");
    public static final Component SETTINGS_CONTOUR_INTERVAL = Component.translatable("world_preview.settings.general.contour_interval");
    public static final Component SETTINGS_CONTOUR_INTERVAL_TOOLTIP = Component.translatable("world_preview.settings.general.contour_interval.tooltip");
    public static final Component SETTINGS_CONTOUR_MINOR = Component.translatable("world_preview.settings.general.contour_minor");
    public static final Component SETTINGS_CONTOUR_MINOR_TOOLTIP = Component.translatable("world_preview.settings.general.contour_minor.tooltip");

    // Error message on setup
    public static final Component MSG_ERROR_SETUP_FAILED = Component.translatable("world_preview.preview.error.setup-failed");
    public static final Component MSG_PREVIEW_SETUP_LOADING = Component.translatable("world_preview.preview.msg.loading");

    // Settings
    public static final Component SETTINGS_TITLE = Component.translatable("world_preview.settings.title");
    public static final Component SETTINGS_RESET_DEFAULTS = Component.translatable("world_preview.settings.reset_defaults");

    // - General settings
    public static final Component SETTINGS_GENERAL_TITLE = Component.translatable("world_preview.settings.general.title");
    public static final Component SETTINGS_GENERAL_HEAD = Component.translatable("world_preview.settings.general.head");
    public static final Component SETTINGS_GENERAL_SECTION_SAMPLING = Component.translatable("world_preview.settings.general.section.sampling");
    public static final Component SETTINGS_GENERAL_SECTION_DISPLAY = Component.translatable("world_preview.settings.general.section.display");
    public static final Component SETTINGS_GENERAL_SECTION_OTHER = Component.translatable("world_preview.settings.general.section.other");
    public static final Component SETTINGS_GENERAL_THREADS = Component.translatable("world_preview.settings.general.threads");
    public static final Component SETTINGS_GENERAL_THREADS_TOOLTIP = Component.translatable("world_preview.settings.general.threads.tooltip");
    public static final Component SETTINGS_GENERAL_FC = Component.translatable("world_preview.settings.general.full.chunk");
    public static final Component SETTINGS_GENERAL_STRUCT = Component.translatable("world_preview.settings.general.struct");
    public static final Component SETTINGS_GENERAL_STRUCT_TOOLTIP = Component.translatable("world_preview.settings.general.struct.tooltip");
    public static final Component SETTINGS_GENERAL_HEIGHTMAP = Component.translatable("world_preview.settings.general.heightmap");
    public static final Component SETTINGS_GENERAL_HEIGHTMAP_TOOLTIP = Component.translatable("world_preview.settings.general.heightmap.tooltip");
    public static final Component SETTINGS_GENERAL_INTERSECT = Component.translatable("world_preview.settings.general.intersect");
    public static final Component SETTINGS_GENERAL_INTERSECT_TOOLTIP = Component.translatable("world_preview.settings.general.intersect.tooltip");
    public static final Component SETTINGS_GENERAL_NOISE = Component.translatable("world_preview.settings.general.noise");
    public static final Component SETTINGS_GENERAL_NOISE_TOOLTIP = Component.translatable("world_preview.settings.general.noise.tooltip");
    public static final Component SETTINGS_GENERAL_FC_TOOLTIP = Component.translatable("world_preview.settings.general.full.chunk.tooltip");
    public static final Component SETTINGS_GENERAL_BG = Component.translatable("world_preview.settings.general.background");
    public static final Component SETTINGS_GENERAL_BG_TOOLTIP = Component.translatable("world_preview.settings.general.background.tooltip");
    public static final Component SETTINGS_GENERAL_CONTROLS = Component.translatable("world_preview.settings.general.controls");
    public static final Component SETTINGS_GENERAL_CONTROLS_TOOLTIP = Component.translatable("world_preview.settings.general.controls.tooltip");
    public static final Component SETTINGS_GENERAL_FRAMETIME = Component.translatable("world_preview.settings.general.frametime");
    public static final Component SETTINGS_GENERAL_FRAMETIME_TOOLTIP = Component.translatable("world_preview.settings.general.frametime.tooltip");
    public static final Component SETTINGS_GENERAL_MINIMAP = Component.translatable("world_preview.settings.general.minimap");
    public static final Component SETTINGS_GENERAL_MINIMAP_TOOLTIP = Component.translatable("world_preview.settings.general.minimap.tooltip");
    public static final Component SETTINGS_GENERAL_STATISTICS = Component.translatable("world_preview.settings.general.statistics");
    public static final Component SETTINGS_GENERAL_STATISTICS_TOOLTIP = Component.translatable("world_preview.settings.general.statistics.tooltip");
    public static final Component SETTINGS_GENERAL_COORDINATES = Component.translatable("world_preview.settings.general.coordinates");
    public static final Component SETTINGS_GENERAL_COORDINATES_TOOLTIP = Component.translatable("world_preview.settings.general.coordinates.tooltip");
    public static final Component SETTINGS_GENERAL_ZOOM = Component.translatable("world_preview.settings.general.zoom");
    public static final Component SETTINGS_GENERAL_ZOOM_TOOLTIP = Component.translatable("world_preview.settings.general.zoom.tooltip");
    public static final Component SETTINGS_GENERAL_PRELOAD = Component.translatable("world_preview.settings.general.preload");
    public static final Component SETTINGS_GENERAL_PRELOAD_TOOLTIP = Component.translatable("world_preview.settings.general.preload.tooltip");
    public static final Component SETTINGS_GENERAL_PRELOAD_IDLE = Component.translatable("world_preview.settings.general.preload_idle");
    public static final Component SETTINGS_GENERAL_PRELOAD_IDLE_TOOLTIP = Component.translatable("world_preview.settings.general.preload_idle.tooltip");
    public static final Component SETTINGS_GENERAL_PRELOAD_RADIUS = Component.translatable("world_preview.settings.general.preload_radius");
    public static final Component SETTINGS_GENERAL_PRELOAD_RADIUS_TOOLTIP = Component.translatable("world_preview.settings.general.preload_radius.tooltip");
    public static final Component SETTINGS_GENERAL_EXPORT = Component.translatable("world_preview.settings.general.export");
    public static final Component SETTINGS_GENERAL_EXPORT_TOOLTIP = Component.translatable("world_preview.settings.general.export.tooltip");
    public static final Component SETTINGS_GENERAL_SHOW_IN_MENU = Component.translatable("world_preview.settings.general.showinmenu");
    public static final Component SETTINGS_GENERAL_SHOW_IN_MENU_TOOLTIP = Component.translatable("world_preview.settings.general.showinmenu.tooltip");
    public static final Component SETTINGS_GENERAL_SHOW_PLAYER = Component.translatable("world_preview.settings.general.showplayer");
    public static final Component SETTINGS_GENERAL_SHOW_PLAYER_TOOLTIP = Component.translatable("world_preview.settings.general.showplayer.tooltip");
    public static final Component SETTINGS_GENERAL_BIOME_COUNTS = Component.translatable("world_preview.settings.general.biome_counts");
    public static final Component SETTINGS_GENERAL_BIOME_COUNTS_TOOLTIP = Component.translatable("world_preview.settings.general.biome_counts.tooltip");
    public static final Component SETTINGS_GENERAL_ANALYSIS_BUTTON = Component.translatable("world_preview.settings.general.analysis_button");
    public static final Component SETTINGS_GENERAL_ANALYSIS_BUTTON_TOOLTIP = Component.translatable("world_preview.settings.general.analysis_button.tooltip");
    public static final Component SETTINGS_GENERAL_SEARCH_MIN_AREA = Component.translatable("world_preview.settings.general.search_min_area");
    public static final Component SETTINGS_GENERAL_SEARCH_MIN_AREA_TOOLTIP = Component.translatable("world_preview.settings.general.search_min_area.tooltip");
    public static final Component SETTINGS_GENERAL_SEARCH_MAX_DISTANCE = Component.translatable("world_preview.settings.general.search_max_distance");
    public static final Component SETTINGS_GENERAL_SEARCH_MAX_DISTANCE_TOOLTIP = Component.translatable("world_preview.settings.general.search_max_distance.tooltip");


    // - Sampling settings
    public static final Component SETTINGS_SAMPLE_TITLE = Component.translatable("world_preview.settings.sample.title");
    public static final Component SETTINGS_SAMPLE_HEAD = Component.translatable("world_preview.settings.sample.head");
    public static final Component SETTINGS_SAMPLE_PIXELS_TITLE_1 = Component.translatable("world_preview.settings.sample.numChunk.title1");
    public static final Component SETTINGS_SAMPLE_PIXELS_TITLE_2 = Component.translatable("world_preview.settings.sample.numChunk.title2");
    public static final Component SETTINGS_SAMPLE_SAMPLE_TITLE_1 = Component.translatable("world_preview.settings.sample.sampler.title1");
    public static final Component SETTINGS_SAMPLE_SAMPLE_TITLE_2 = Component.translatable("world_preview.settings.sample.sampler.title2");

    // - Caching settings
    public static final Component SETTINGS_CACHE_TITLE = Component.translatable("world_preview.settings.cache.title");
    public static final Component SETTINGS_CACHE_DESC = Component.translatable("world_preview.settings.cache.desc");
    public static final Component SETTINGS_CACHE_G_ENABLE = Component.translatable("world_preview.settings.cache.game.enable");
    public static final Component SETTINGS_CACHE_N_ENABLE = Component.translatable("world_preview.settings.cache.new.enable");
    public static final Component SETTINGS_CACHE_CLEAR = Component.translatable("world_preview.settings.cache.clear");
    public static final Component SETTINGS_CACHE_CLEAR_TOOLTIP = Component.translatable("world_preview.settings.cache.clear.tooltip");
    public static final Component SETTINGS_CACHE_CLEAR_FAILED = Component.translatable("world_preview.settings.cache.clear.failed");
    public static final Component SETTINGS_CACHE_COMPRESSION = Component.translatable("world_preview.settings.cache.compression");
    public static final Component SETTINGS_CACHE_COMPRESSION_TOOLTIP = Component.translatable("world_preview.settings.cache.compression.tooltip");

    // - Heightmap settings
    public static final Component SETTINGS_HEIGHTMAP_TITLE = Component.translatable("world_preview.settings.heightmap.title");
    public static final Component SETTINGS_HEIGHTMAP_DISABLED = Component.translatable("world_preview.settings.heightmap.disabled");
    public static final Component SETTINGS_HEIGHTMAP_PRESETS = Component.translatable("world_preview.settings.heightmap.presets");
    public static final Component SETTINGS_HEIGHTMAP_COLORMAP = Component.translatable("world_preview.settings.heightmap.colormap");
    public static final Component SETTINGS_HEIGHTMAP_MIN_Y = Component.translatable("world_preview.settings.heightmap.minY");
    public static final Component SETTINGS_HEIGHTMAP_MAX_Y = Component.translatable("world_preview.settings.heightmap.maxY");
    public static final Component SETTINGS_HEIGHTMAP_MIN_Y_TOOLTIP = Component.translatable("world_preview.settings.heightmap.minY.tooltip");
    public static final Component SETTINGS_HEIGHTMAP_MAX_Y_TOOLTIP = Component.translatable("world_preview.settings.heightmap.maxY.tooltip");
    public static final Component SETTINGS_HEIGHTMAP_VISUAL = Component.translatable("world_preview.settings.heightmap.visual");
    public static final Component SETTINGS_HEIGHTMAP_VISUAL_TOOLTIP = Component.translatable("world_preview.settings.heightmap.visual.tooltip");

    // - Dimensions settings
    public static final Component SETTINGS_DIM_TITLE = Component.translatable("world_preview.settings.dimensions.title");
    public static final Component SETTINGS_DIM_HEAD = Component.translatable("world_preview.settings.dimensions.head");
    public static final Component SETTINGS_DIM_EMPTY = Component.translatable("world_preview.settings.dimensions.empty");

    // - Biome color chooser
    public static final Component SETTINGS_BIOMES_TITLE = Component.translatable("world_preview.settings.biomes.title");
    public static final Component SETTINGS_BIOMES_OPEN = Component.translatable("world_preview.settings.biomes.open");
    public static final Component SETTINGS_BIOMES_OPEN_DESC = Component.translatable("world_preview.settings.biomes.open.desc");

    public static final Component COLOR_HUE = Component.translatable("world_preview.color.picker.hue");
    public static final Component COLOR_SAT = Component.translatable("world_preview.color.picker.saturation");
    public static final Component COLOR_VAL = Component.translatable("world_preview.color.picker.value");
    public static final Component COLOR_R = Component.translatable("world_preview.color.picker.r");
    public static final Component COLOR_G = Component.translatable("world_preview.color.picker.g");
    public static final Component COLOR_B = Component.translatable("world_preview.color.picker.b");

    public static final Component COLOR_CAVE = Component.translatable("world_preview.settings.biomes.cave");
    public static final Component COLOR_RESET = Component.translatable("world_preview.settings.biomes.reset");
    public static final Component COLOR_APPLY = Component.translatable("world_preview.settings.biomes.apply");
    public static final Component COLOR_LIST_FILTER = Component.translatable("world_preview.settings.biomes.filter");
    public static final Component BIOME_SOURCE = Component.translatable("world_preview.tooltip.biome.source");



}
