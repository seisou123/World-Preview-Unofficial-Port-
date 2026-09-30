package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SeedSearchRequestTest {

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void constructorRejectsInvalidParameters(String biomeId, int maxAttempts, int sampleStep) {
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                new ResourceLocation(biomeId), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, sampleStep, "test", maxAttempts, 0, 0
        ));
    }

    private static Stream<Arguments> invalidParameters() {
        return Stream.of(
                Arguments.of("minecraft:plains", -1, 4),
                Arguments.of("minecraft:desert", 100, 0)
        );
    }
}