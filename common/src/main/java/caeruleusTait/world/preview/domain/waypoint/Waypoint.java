package caeruleusTait.world.preview.domain.waypoint;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * A user-placed marker on the preview map.
 * <p>
 * Waypoints are scoped to a (seed, dimension) pair so markers never point at
 * wrong terrain after the seed or dimension changes.
 * </p>
 *
 * @param id        stable identifier (UUID string)
 * @param name      user-chosen label
 * @param x         block X
 * @param y         block Y (the map layer the marker was placed on)
 * @param z         block Z
 * @param dimension dimension identifier ("namespace:path")
 * @param color     ARGB color of the pin
 * @param seed      world seed this marker belongs to
 */
public record Waypoint(
        String id,
        String name,
        int x,
        int y,
        int z,
        String dimension,
        int color,
        long seed
) {
    public Waypoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimension, "dimension");
        if (name.isBlank()) {
            name = "waypoint";
        }
    }

    public static Waypoint create(String name, int x, int y, int z, String dimension, int color, long seed) {
        return new Waypoint(UUID.randomUUID().toString(), name, x, y, z, dimension, color, seed);
    }

    public boolean matches(long seed, @Nullable String dimension) {
        return this.seed == seed && (dimension == null || this.dimension.equals(dimension));
    }
}
