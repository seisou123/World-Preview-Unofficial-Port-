package caeruleusTait.world.preview.domain.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for settings pages, mapping {@link PageCategory} to {@link ConfigurablePage} instances.
 *
 * <p>Maintains insertion order so that the UI sidebar can iterate pages in a stable sequence.
 * Thread-safe for single-threaded UI access patterns.
 */
public class PageRegistry {

    private final Map<PageCategory, ConfigurablePage> pages = new EnumMap<>(PageCategory.class);
    private final List<PageCategory> order = new ArrayList<>();

    /**
     * Registers a page under the given category.
     * If a page was already registered for this category, it is replaced.
     *
     * @param category the page category
     * @param page the configurable page
     */
    public void register(PageCategory category, ConfigurablePage page) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(page, "page");
        if (!pages.containsKey(category)) {
            order.add(category);
        }
        pages.put(category, page);
    }

    /** Returns the page registered under the given category, or {@code null} if none. */
    public ConfigurablePage get(PageCategory category) {
        return pages.get(category);
    }

    /** Returns all registered pages in insertion order. */
    public List<ConfigurablePage> pages() {
        return order.stream().map(pages::get).toList();
    }

    /** Returns all registered categories in insertion order. */
    public List<PageCategory> categories() {
        return List.copyOf(order);
    }

    /** Returns the index of the given category, or -1 if not registered. */
    public int indexOf(PageCategory category) {
        return order.indexOf(category);
    }

    /** Returns the page at the given index, or {@code null} if out of bounds. */
    public ConfigurablePage pageAt(int index) {
        if (index < 0 || index >= order.size()) return null;
        return pages.get(order.get(index));
    }

    /** Returns the category at the given index, or {@code null} if out of bounds. */
    public PageCategory categoryAt(int index) {
        if (index < 0 || index >= order.size()) return null;
        return order.get(index);
    }

    /** Returns the number of registered pages. */
    public int size() {
        return pages.size();
    }

    /** Returns {@code true} if no pages are registered. */
    public boolean isEmpty() {
        return pages.isEmpty();
    }

    /** Removes all registered pages. */
    public void clear() {
        pages.clear();
        order.clear();
    }

    /**
     * Validates all registered pages and returns a list of error messages.
     * An empty list means all pages are valid.
     */
    public List<String> validateAll() {
        List<String> errors = new ArrayList<>();
        for (ConfigurablePage page : pages()) {
            errors.addAll(page.validateBindings());
        }
        return errors;
    }

    /** Resets all registered pages to their default values. */
    public void resetAll() {
        for (ConfigurablePage page : pages()) {
            page.resetDefaults();
        }
    }
}
