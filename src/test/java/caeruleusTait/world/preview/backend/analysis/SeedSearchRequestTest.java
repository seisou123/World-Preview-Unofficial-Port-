package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedSearchRequestTest {

    @Test
    void constructorRejectsNegativeMaxAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                Identifier.parse("minecraft:plains"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, 4, "test", -1, 0, 0
        ));
    }

    @Test
    void constructorRejectsZeroSampleStep() {
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                Identifier.parse("minecraft:desert"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, 0, "test", 100, 0, 0
        ));
    }

    @Test
    void constructorAcceptsValidParameters() {
        var req = new SeedSearchRequest(
                Identifier.parse("minecraft:plains"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, 4, "abc123", 100, 0, 0
        );
        assertEquals(Identifier.parse("minecraft:plains"), req.targetBiome());
        assertEquals(100, req.maxAttempts());
    }
}