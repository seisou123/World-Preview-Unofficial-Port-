package caeruleusTait.world.preview.domain.ui;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Type-safe binding between a configuration key and a value.
 *
 * <p>Supports Int, Boolean, Enum, String, and Color bindings.
 */
public abstract class ConfigBinding<T> {

    private final String key;
    private final Class<T> type;
    private final Supplier<T> getter;
    private final Consumer<T> setter;
    private final T defaultValue;
    private final T minValue;
    private final T maxValue;

    protected ConfigBinding(String key, Class<T> type, Supplier<T> getter, Consumer<T> setter,
                            T defaultValue, T minValue, T maxValue) {
        this.key = requireText(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.getter = Objects.requireNonNull(getter, "getter");
        this.setter = Objects.requireNonNull(setter, "setter");
        this.defaultValue = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    /** Returns the configuration key. */
    public String key() { return key; }

    /** Returns the value type. */
    public Class<T> type() { return type; }

    /** Returns the current value. */
    public T get() { return getter.get(); }

    /** Sets a new value, validating type and range. */
    public void set(T value) {
        Objects.requireNonNull(value, "value");
        if (!type.isInstance(value)) {
            throw new ConfigBindingException("expected " + type.getName() + ", got " + value.getClass().getName());
        }
        validateRange(value);
        setter.accept(value);
    }

    /** Returns the default value. */
    public T defaultValue() { return defaultValue; }

    /** Resets to the default value. */
    public void reset() {
        if (defaultValue != null) set(defaultValue);
    }

    /** Returns the minimum allowed value, or {@code null} if unbounded. */
    public T minValue() { return minValue; }

    /** Returns the maximum allowed value, or {@code null} if unbounded. */
    public T maxValue() { return maxValue; }

    /** Validates the current value against type and range constraints. */
    public void validate() {
        T value = get();
        if (value == null) {
            throw new ConfigBindingException(key + ": value is null");
        }
        if (!type.isInstance(value)) {
            throw new ConfigBindingException("expected " + type.getName() + ", got " + value.getClass().getName());
        }
        validateRange(value);
    }

    /** Validates the value against the min/max range. */
    @SuppressWarnings("unchecked")
    protected void validateRange(T value) {
        if (minValue != null && value instanceof Comparable) {
            if (((Comparable<T>) value).compareTo(minValue) < 0) {
                throw new ConfigBindingException(key + ": value " + value + " is below minimum " + minValue);
            }
        }
        if (maxValue != null && value instanceof Comparable) {
            if (((Comparable<T>) value).compareTo(maxValue) > 0) {
                throw new ConfigBindingException(key + ": value " + value + " exceeds maximum " + maxValue);
            }
        }
    }

    // ---- Factory methods ----

    public static ConfigBinding<Integer> intBinding(String key, Supplier<Integer> getter, Consumer<Integer> setter,
                                                     int defaultValue, int min, int max) {
        return new ConfigBinding<>(key, Integer.class, getter, setter, defaultValue, min, max) {};
    }

    public static ConfigBinding<Boolean> booleanBinding(String key, Supplier<Boolean> getter, Consumer<Boolean> setter,
                                                         boolean defaultValue) {
        return new ConfigBinding<>(key, Boolean.class, getter, setter, defaultValue, null, null) {};
    }

    public static <E extends Enum<E>> ConfigBinding<E> enumBinding(String key, Class<E> enumType,
                                                                     Supplier<E> getter, Consumer<E> setter,
                                                                     E defaultValue) {
        return new ConfigBinding<>(key, enumType, getter, setter, defaultValue, null, null) {};
    }

    public static ConfigBinding<String> stringBinding(String key, Supplier<String> getter, Consumer<String> setter,
                                                       String defaultValue) {
        return new ConfigBinding<>(key, String.class, getter, setter, defaultValue, null, null) {};
    }

    public static ConfigBinding<Integer> colorBinding(String key, Supplier<Integer> getter, Consumer<Integer> setter,
                                                       int defaultValue) {
        return new ConfigBinding<>(key, Integer.class, getter, setter, defaultValue, 0, 0xFFFFFF) {};
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
