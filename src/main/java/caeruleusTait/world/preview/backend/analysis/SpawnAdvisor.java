package caeruleusTait.world.preview.backend.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic spawn-point quality advisor.
 *
 * <p>Pure backend type: no Minecraft imports, safe to use from tests and from
 * background threads. Callers translate the returned {@link Reason} keys with
 * {@code Component.translatable(key, args)} on the client side; no user-facing
 * strings are hardcoded here.</p>
 *
 * <p><b>Scoring weights</b> (the weighted sum is rounded once and is 0..100 by
 * construction):</p>
 * <ul>
 *   <li><b>Flatness</b> — {@code 30 * flatRatio}, up to <b>30</b> points
 *       (flatRatio 1.0 = perfectly flat region).</li>
 *   <li><b>Slope</b> — {@code 20 * (1 - min(1, meanSlope / 12))}, up to <b>20</b>
 *       points; a mean slope of 12+ (height delta per sample step) scores 0.
 *       Unknown slope (null) receives half credit (<b>10</b> points) and produces
 *       no slope reason.</li>
 *   <li><b>Water</b> — {@code 30 * (1 - waterShare)}, up to <b>30</b> points;
 *       fully land scores 30, fully water scores 0.</li>
 *   <li><b>Structures</b> — {@code min(20, structuresNearby * 10)}, a bonus of
 *       up to <b>20</b> points (2+ structures in reach saturate it).</li>
 * </ul>
 */
public final class SpawnAdvisor {

    /** Weight of the flatness component (points at flatRatio == 1.0). */
    public static final int FLAT_WEIGHT = 30;
    /** Weight of the slope component (points at meanSlope == 0; unknown slope gets half). */
    public static final int SLOPE_WEIGHT = 20;
    /** Mean slope (height delta per sample step) at or above which the slope score is 0. */
    public static final double STEEP_SLOPE = 12.0;
    /** Weight of the water component (points at waterShare == 0). */
    public static final int WATER_WEIGHT = 30;
    /** Weight cap of the structure bonus. */
    public static final int STRUCTURE_WEIGHT = 20;
    /** Points added per nearby structure. */
    public static final int STRUCTURE_POINTS_EACH = 10;

    /** Reason translation keys, prefixed with this constant. */
    public static final String REASON_KEY_PREFIX = "world_preview.spawnadvisor.reason.";

    private SpawnAdvisor() {}

    /**
     * Inputs for {@link #evaluate(SpawnInput)}. All ratios are clamped to 0..1
     * (non-finite values become 0.0); {@code meanSlope} may be null when slope
     * data is unavailable.
     */
    public record SpawnInput(double waterShare, double flatRatio, Double meanSlope, int structuresNearby) {
        public SpawnInput {
            waterShare = clampUnit(waterShare);
            flatRatio = clampUnit(flatRatio);
            if (structuresNearby < 0) {
                throw new IllegalArgumentException("structuresNearby must be >= 0");
            }
        }

        private static double clampUnit(double value) {
            if (!Double.isFinite(value)) {
                return 0.0;
            }
            return value < 0.0 ? 0.0 : Math.min(1.0, value);
        }
    }

    /** Score (0..100) plus the decisive reasons, given as translation key + args. */
    public record SpawnResult(int score, List<Reason> reasons) {
        public SpawnResult {
            score = Math.max(0, Math.min(100, score));
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }

    /**
     * One decisive evaluation outcome. {@code key} is a
     * {@code world_preview.spawnadvisor.reason.*} translation key; {@code args}
     * are the positional arguments for {@code Component.translatable(key, args)}.
     */
    public record Reason(String key, Object[] args) {
        public Reason {
            Objects.requireNonNull(key, "key");
            args = args == null ? new Object[0] : args.clone();
        }

        public static Reason of(String key, Object... args) {
            return new Reason(key, args);
        }
    }

    /**
     * Score the given spawn inputs and collect the decisive reasons.
     *
     * <p>Reasons are only included when decisive: much/little water, rough/flat
     * terrain, steep slope, structures nearby, and an overall good/poor verdict.</p>
     */
    public static SpawnResult evaluate(SpawnInput input) {
        Objects.requireNonNull(input, "input");
        double waterShare = input.waterShare();
        double flatRatio = input.flatRatio();
        Double meanSlope = input.meanSlope();
        int structures = input.structuresNearby();

        double flatScore = FLAT_WEIGHT * flatRatio;
        double slopeScore;
        if (meanSlope == null) {
            // Unknown slope: half credit, no reason.
            slopeScore = SLOPE_WEIGHT / 2.0;
        } else {
            double normalized = Math.min(1.0, Math.max(0.0, meanSlope / STEEP_SLOPE));
            slopeScore = SLOPE_WEIGHT * (1.0 - normalized);
        }
        double waterScore = WATER_WEIGHT * (1.0 - waterShare);
        double structureScore = Math.min(STRUCTURE_WEIGHT, (double) structures * STRUCTURE_POINTS_EACH);

        int score = clamp((int) Math.round(flatScore + slopeScore + waterScore + structureScore));

        List<Reason> reasons = new ArrayList<>();
        if (waterShare > 0.6) {
            reasons.add(Reason.of(REASON_KEY_PREFIX + "much_water", percent(waterShare)));
        } else if (waterShare < 0.1) {
            reasons.add(Reason.of(REASON_KEY_PREFIX + "little_water"));
        }
        if (flatRatio < 0.3) {
            reasons.add(Reason.of(REASON_KEY_PREFIX + "rough_terrain"));
        } else if (flatRatio > 0.7) {
            reasons.add(Reason.of(REASON_KEY_PREFIX + "flat_terrain"));
        }
        if (meanSlope != null && meanSlope > STEEP_SLOPE) {
            reasons.add(Reason.of(REASON_KEY_PREFIX + "steep", number(meanSlope)));
        }
        if (structures > 0) {
            reasons.add(Reason.of(REASON_KEY_PREFIX + "structures_nearby", structures));
        }
        if (score >= 70) {
            reasons.add(Reason.of(REASON_KEY_PREFIX + "good_spawn"));
        } else if (score < 40) {
            reasons.add(Reason.of(REASON_KEY_PREFIX + "poor_spawn"));
        }

        return new SpawnResult(score, List.copyOf(reasons));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.0f%%", share * 100.0);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
