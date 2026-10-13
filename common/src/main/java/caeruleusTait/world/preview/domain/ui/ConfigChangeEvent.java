package caeruleusTait.world.preview.domain.ui;

import java.util.Objects;

/**
 * Immutable event fired when a configuration value changes.
 */
public record ConfigChangeEvent(
        String key,
        Object oldValue,
        Object newValue
) {
    public ConfigChangeEvent {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) throw new IllegalArgumentException("key must not be blank");
    }

    /** Returns {@code true} if the value actually changed. */
    public boolean hasChanged() {
        return !Objects.equals(oldValue, newValue);
    }
}
