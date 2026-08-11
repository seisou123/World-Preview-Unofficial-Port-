package caeruleusTait.world.preview.domain.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the domain/ui module.
 *
 * <p>Covers {@link ConfigBinding}, {@link ConfigurablePage}, {@link PageRegistry},
 * {@link ConfigChangeEvent}, {@link ControlLayout}, {@link ColorEditor}, and
 * {@link PageCategory}.
 */
class UIDomainTest {

    // ---- ConfigBinding tests ----

    @Test
    void intBindingValidatesRange() {
        AtomicInteger holder = new AtomicInteger(5);
        ConfigBinding<Integer> binding = ConfigBinding.intBinding(
                "test.int", holder::get, holder::set, 5, 0, 10);

        binding.set(7);
        assertEquals(7, binding.get());
        assertEquals(7, holder.get());

        assertThrows(ConfigBindingException.class, () -> binding.set(15));
        assertThrows(ConfigBindingException.class, () -> binding.set(-1));
        // Value should still be 7 after failed sets
        assertEquals(7, holder.get());
    }

    @Test
    void intBindingResetsToDefault() {
        AtomicInteger holder = new AtomicInteger(5);
        ConfigBinding<Integer> binding = ConfigBinding.intBinding(
                "test.int", holder::get, holder::set, 5, 0, 10);

        binding.set(8);
        assertEquals(8, holder.get());
        binding.reset();
        assertEquals(5, holder.get());
    }

    @Test
    void booleanBindingWorks() {
        var holder = new AtomicReference<>(true);
        ConfigBinding<Boolean> binding = ConfigBinding.booleanBinding(
                "test.bool", holder::get, holder::set, true);

        assertTrue(binding.get());
        binding.set(false);
        assertFalse(binding.get());
        assertFalse(holder.get());
        binding.reset();
        assertTrue(binding.get());
    }

    @Test
    void stringBindingWorks() {
        var holder = new AtomicReference<>("default");
        ConfigBinding<String> binding = ConfigBinding.stringBinding(
                "test.string", holder::get, holder::set, "default");

        assertEquals("default", binding.get());
        binding.set("custom");
        assertEquals("custom", binding.get());
        assertEquals("custom", holder.get());
        binding.reset();
        assertEquals("default", holder.get());
    }

    @Test
    void enumBindingWorks() {
        var holder = new AtomicReference<>(PageCategory.GENERAL);
        ConfigBinding<PageCategory> binding = ConfigBinding.enumBinding(
                "test.enum", PageCategory.class, holder::get, holder::set, PageCategory.GENERAL);

        assertEquals(PageCategory.GENERAL, binding.get());
        binding.set(PageCategory.CACHE);
        assertEquals(PageCategory.CACHE, holder.get());
        binding.reset();
        assertEquals(PageCategory.GENERAL, holder.get());
    }

    @Test
    void colorBindingValidatesRange() {
        AtomicInteger holder = new AtomicInteger(0xFF0000);
        ConfigBinding<Integer> binding = ConfigBinding.colorBinding(
                "test.color", holder::get, holder::set, 0xFF0000);

        binding.set(0x00FF00);
        assertEquals(0x00FF00, binding.get());
        assertThrows(ConfigBindingException.class, () -> binding.set(0xFFFFFF + 1));
        assertThrows(ConfigBindingException.class, () -> binding.set(-1));
    }

    @Test
    void bindingRejectsNullValue() {
        ConfigBinding<String> binding = ConfigBinding.stringBinding(
                "test.null", () -> "x", v -> {}, "x");
        assertThrows(NullPointerException.class, () -> binding.set(null));
    }

    @Test
    void bindingRejectsBlankKey() {
        assertThrows(IllegalArgumentException.class, () ->
                ConfigBinding.intBinding("", () -> 0, v -> {}, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigBinding.intBinding("  ", () -> 0, v -> {}, 0, 0, 1));
    }

    // ---- ConfigurablePage tests ----

    @Test
    void configurablePageRegistersAndValidatesBindings() {
        AtomicInteger holder = new AtomicInteger(5);
        ConfigBinding<Integer> binding = ConfigBinding.intBinding(
                "page.int", holder::get, holder::set, 5, 0, 10);

        TestPage page = new TestPage("test", PageCategory.GENERAL);
        page.addBinding(binding);

        assertTrue(page.validateBindings().isEmpty());
        holder.set(15);
        assertFalse(page.validateBindings().isEmpty());
    }

    @Test
    void configurablePageResetDefaults() {
        AtomicInteger holder = new AtomicInteger(5);
        ConfigBinding<Integer> binding = ConfigBinding.intBinding(
                "page.int", holder::get, holder::set, 5, 0, 10);

        TestPage page = new TestPage("test", PageCategory.GENERAL);
        page.addBinding(binding);

        binding.set(8);
        assertEquals(8, holder.get());
        page.resetDefaults();
        assertEquals(5, holder.get());
    }

    @Test
    void configurablePageFiresConfigChangeEvents() {
        TestPage page = new TestPage("test", PageCategory.GENERAL);
        List<ConfigChangeEvent> events = new ArrayList<>();
        page.addChangeListener(events::add);

        ConfigChangeEvent event = new ConfigChangeEvent("key1", "old", "new");
        page.fireChange(event);

        assertEquals(1, events.size());
        assertEquals("key1", events.get(0).key());
        assertEquals("old", events.get(0).oldValue());
        assertEquals("new", events.get(0).newValue());
    }

    @Test
    void configurablePageRejectsBlankTitle() {
        assertThrows(IllegalArgumentException.class, () -> new TestPage("", PageCategory.GENERAL));
        assertThrows(IllegalArgumentException.class, () -> new TestPage("  ", PageCategory.GENERAL));
    }

    @Test
    void configurablePageReturnsBindings() {
        TestPage page = new TestPage("test", PageCategory.GENERAL);
        ConfigBinding<Integer> b1 = ConfigBinding.intBinding("k1", () -> 0, v -> {}, 0, 0, 1);
        ConfigBinding<Boolean> b2 = ConfigBinding.booleanBinding("k2", () -> true, v -> {}, true);

        page.addBinding(b1);
        page.addBinding(b2);

        assertEquals(2, page.bindings().size());
    }

    // ---- ConfigChangeEvent tests ----

    @Test
    void configChangeEventIsImmutable() {
        ConfigChangeEvent event = new ConfigChangeEvent("key", 1, 2);
        assertEquals("key", event.key());
        assertEquals(1, event.oldValue());
        assertEquals(2, event.newValue());
    }

    @Test
    void configChangeEventHasChanged() {
        assertTrue(new ConfigChangeEvent("k", 1, 2).hasChanged());
        assertFalse(new ConfigChangeEvent("k", 1, 1).hasChanged());
        assertFalse(new ConfigChangeEvent("k", null, null).hasChanged());
        assertTrue(new ConfigChangeEvent("k", null, "x").hasChanged());
    }

    @Test
    void configChangeEventRejectsBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigChangeEvent("", 1, 2));
    }

    // ---- ControlLayout tests ----

    @Test
    void controlLayoutRowFactory() {
        ControlLayout layout = ControlLayout.row(3, 8);
        assertEquals(ControlLayout.Orientation.ROW, layout.orientation());
        assertEquals(1, layout.rows());
        assertEquals(3, layout.columns());
        assertEquals(8, layout.horizontalGap());
    }

    @Test
    void controlLayoutColumnFactory() {
        ControlLayout layout = ControlLayout.column(5, 4);
        assertEquals(ControlLayout.Orientation.COLUMN, layout.orientation());
        assertEquals(5, layout.rows());
        assertEquals(1, layout.columns());
    }

    @Test
    void controlLayoutGridFactory() {
        ControlLayout layout = ControlLayout.grid(3, 4, 6);
        assertEquals(ControlLayout.Orientation.GRID, layout.orientation());
        assertEquals(3, layout.rows());
        assertEquals(4, layout.columns());
    }

    @Test
    void controlLayoutClampsNegativeValues() {
        ControlLayout layout = new ControlLayout(ControlLayout.Orientation.ROW, -1, -1, -1, -1, -1);
        assertEquals(1, layout.rows());
        assertEquals(1, layout.columns());
        assertEquals(0, layout.horizontalGap());
        assertEquals(0, layout.verticalGap());
        assertEquals(0, layout.padding());
    }

    // ---- PageRegistry tests ----

    @Test
    void pageRegistryRegisterAndGet() {
        PageRegistry registry = new PageRegistry();
        TestPage general = new TestPage("general", PageCategory.GENERAL);
        TestPage cache = new TestPage("cache", PageCategory.CACHE);

        registry.register(PageCategory.GENERAL, general);
        registry.register(PageCategory.CACHE, cache);

        assertSame(general, registry.get(PageCategory.GENERAL));
        assertSame(cache, registry.get(PageCategory.CACHE));
        assertNull(registry.get(PageCategory.BIOME));
        assertEquals(2, registry.size());
    }

    @Test
    void pageRegistryMaintainsInsertionOrder() {
        PageRegistry registry = new PageRegistry();
        registry.register(PageCategory.CACHE, new TestPage("cache", PageCategory.CACHE));
        registry.register(PageCategory.GENERAL, new TestPage("general", PageCategory.GENERAL));
        registry.register(PageCategory.BIOME, new TestPage("biome", PageCategory.BIOME));

        List<PageCategory> categories = registry.categories();
        assertEquals(3, categories.size());
        assertEquals(PageCategory.CACHE, categories.get(0));
        assertEquals(PageCategory.GENERAL, categories.get(1));
        assertEquals(PageCategory.BIOME, categories.get(2));

        assertEquals(0, registry.indexOf(PageCategory.CACHE));
        assertEquals(1, registry.indexOf(PageCategory.GENERAL));
        assertEquals(2, registry.indexOf(PageCategory.BIOME));
    }

    @Test
    void pageRegistryPageAt() {
        PageRegistry registry = new PageRegistry();
        TestPage page = new TestPage("test", PageCategory.GENERAL);
        registry.register(PageCategory.GENERAL, page);

        assertSame(page, registry.pageAt(0));
        assertNull(registry.pageAt(1));
        assertNull(registry.pageAt(-1));
    }

    @Test
    void pageRegistryCategoryAt() {
        PageRegistry registry = new PageRegistry();
        registry.register(PageCategory.CACHE, new TestPage("cache", PageCategory.CACHE));

        assertEquals(PageCategory.CACHE, registry.categoryAt(0));
        assertNull(registry.categoryAt(1));
    }

    @Test
    void pageRegistryValidateAll() {
        PageRegistry registry = new PageRegistry();
        TestPage page1 = new TestPage("p1", PageCategory.GENERAL);
        TestPage page2 = new TestPage("p2", PageCategory.CACHE);

        AtomicInteger holder1 = new AtomicInteger(5);
        AtomicInteger holder2 = new AtomicInteger(15); // out of range
        page1.addBinding(ConfigBinding.intBinding("k1", holder1::get, holder1::set, 5, 0, 10));
        page2.addBinding(ConfigBinding.intBinding("k2", holder2::get, holder2::set, 5, 0, 10));

        registry.register(PageCategory.GENERAL, page1);
        registry.register(PageCategory.CACHE, page2);

        List<String> errors = registry.validateAll();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("k2"));
    }

    @Test
    void pageRegistryResetAll() {
        PageRegistry registry = new PageRegistry();
        TestPage page1 = new TestPage("p1", PageCategory.GENERAL);
        TestPage page2 = new TestPage("p2", PageCategory.CACHE);

        AtomicInteger h1 = new AtomicInteger(5);
        AtomicInteger h2 = new AtomicInteger(10);
        ConfigBinding<Integer> b1 = ConfigBinding.intBinding("k1", h1::get, h1::set, 5, 0, 100);
        ConfigBinding<Integer> b2 = ConfigBinding.intBinding("k2", h2::get, h2::set, 10, 0, 100);
        page1.addBinding(b1);
        page2.addBinding(b2);

        registry.register(PageCategory.GENERAL, page1);
        registry.register(PageCategory.CACHE, page2);

        b1.set(50);
        b2.set(80);
        assertEquals(50, h1.get());
        assertEquals(80, h2.get());

        registry.resetAll();
        assertEquals(5, h1.get());
        assertEquals(10, h2.get());
    }

    @Test
    void pageRegistryReplaceExisting() {
        PageRegistry registry = new PageRegistry();
        TestPage original = new TestPage("original", PageCategory.GENERAL);
        TestPage replacement = new TestPage("replacement", PageCategory.GENERAL);

        registry.register(PageCategory.GENERAL, original);
        registry.register(PageCategory.GENERAL, replacement);

        assertSame(replacement, registry.get(PageCategory.GENERAL));
        assertEquals(1, registry.size());
    }

    @Test
    void pageRegistryClear() {
        PageRegistry registry = new PageRegistry();
        registry.register(PageCategory.GENERAL, new TestPage("g", PageCategory.GENERAL));
        registry.register(PageCategory.CACHE, new TestPage("c", PageCategory.CACHE));

        assertFalse(registry.isEmpty());
        registry.clear();
        assertTrue(registry.isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void pageRegistryRejectsNulls() {
        PageRegistry registry = new PageRegistry();
        assertThrows(NullPointerException.class, () -> registry.register(null, new TestPage("t", PageCategory.GENERAL)));
        assertThrows(NullPointerException.class, () -> registry.register(PageCategory.GENERAL, null));
    }

    // ---- ColorEditor tests ----

    @Test
    void rgbToHsvRed() {
        float[] hsv = ColorEditor.rgbToHsv(255, 0, 0);
        assertEquals(0f, hsv[0], 0.01f);   // hue
        assertEquals(1f, hsv[1], 0.01f);   // saturation
        assertEquals(1f, hsv[2], 0.01f);   // value
    }

    @Test
    void rgbToHsvGreen() {
        float[] hsv = ColorEditor.rgbToHsv(0, 255, 0);
        assertEquals(120f, hsv[0], 0.01f);
        assertEquals(1f, hsv[1], 0.01f);
        assertEquals(1f, hsv[2], 0.01f);
    }

    @Test
    void rgbToHsvBlue() {
        float[] hsv = ColorEditor.rgbToHsv(0, 0, 255);
        assertEquals(240f, hsv[0], 0.01f);
        assertEquals(1f, hsv[1], 0.01f);
        assertEquals(1f, hsv[2], 0.01f);
    }

    @Test
    void rgbToHsvBlack() {
        float[] hsv = ColorEditor.rgbToHsv(0, 0, 0);
        assertEquals(0f, hsv[0], 0.01f);
        assertEquals(0f, hsv[1], 0.01f);
        assertEquals(0f, hsv[2], 0.01f);
    }

    @Test
    void rgbToHsvWhite() {
        float[] hsv = ColorEditor.rgbToHsv(255, 255, 255);
        assertEquals(0f, hsv[0], 0.01f);
        assertEquals(0f, hsv[1], 0.01f);
        assertEquals(1f, hsv[2], 0.01f);
    }

    @Test
    void hsvToRgbRed() {
        int rgb = ColorEditor.hsvToRgb(0, 1, 1);
        assertEquals(0xFF0000, rgb);
    }

    @Test
    void hsvToRgbGreen() {
        int rgb = ColorEditor.hsvToRgb(120, 1, 1);
        assertEquals(0x00FF00, rgb);
    }

    @Test
    void hsvToRgbBlue() {
        int rgb = ColorEditor.hsvToRgb(240, 1, 1);
        assertEquals(0x0000FF, rgb);
    }

    @Test
    void hsvToRgbBlack() {
        int rgb = ColorEditor.hsvToRgb(0, 0, 0);
        assertEquals(0x000000, rgb);
    }

    @Test
    void hsvToRgbWhite() {
        int rgb = ColorEditor.hsvToRgb(0, 0, 1);
        assertEquals(0xFFFFFF, rgb);
    }

    @Test
    void rgbHsvRoundTrip() {
        int[] testColors = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0x808080, 0x336699};
        for (int rgb : testColors) {
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            float[] hsv = ColorEditor.rgbToHsv(r, g, b);
            int converted = ColorEditor.hsvToRgb(hsv[0], hsv[1], hsv[2]);
            // Allow for rounding differences (±1 per channel)
            int dr = Math.abs(r - ((converted >> 16) & 0xFF));
            int dg = Math.abs(g - ((converted >> 8) & 0xFF));
            int db = Math.abs(b - (converted & 0xFF));
            assertTrue(dr <= 1 && dg <= 1 && db <= 1,
                    "Round-trip failed for " + Integer.toHexString(rgb) + ": got " + Integer.toHexString(converted));
        }
    }

    // ---- PageCategory tests ----

    @Test
    void pageCategoryHasExpectedValues() {
        PageCategory[] categories = PageCategory.values();
        assertEquals(6, categories.length);
        assertTrue(List.of(categories).containsAll(List.of(
                PageCategory.GENERAL,
                PageCategory.CACHE,
                PageCategory.RESOLUTION,
                PageCategory.HEIGHTMAP,
                PageCategory.DIMENSION,
                PageCategory.BIOME
        )));
    }

    // ---- Helper test class ----

    private static class TestPage extends ConfigurablePage {
        TestPage(String title, PageCategory category) {
            super(title, category);
        }

        void addBinding(ConfigBinding<?> binding) {
            bind(binding);
        }

        void fireChange(ConfigChangeEvent event) {
            fireConfigChange(event);
        }
    }
}
