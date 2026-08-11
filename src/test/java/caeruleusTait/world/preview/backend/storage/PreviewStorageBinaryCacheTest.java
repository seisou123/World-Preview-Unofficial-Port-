package caeruleusTait.world.preview.backend.storage;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Round-trip tests for the safe non-Java-serialization binary cache format.
 */
class PreviewStorageBinaryCacheTest {

    @BeforeEach
    void initializeWorldPreview() throws Exception {
        WorldPreview preview = new WorldPreview();
        Field instance = WorldPreview.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, preview);
        Field cfg = WorldPreview.class.getDeclaredField("cfg");
        cfg.setAccessible(true);
        WorldPreviewConfig config = WorldPreviewConfig.defaults();
        config.enableCompression = false;
        cfg.set(preview, config);
        Field settings = WorldPreview.class.getDeclaredField("renderSettings");
        settings.setAccessible(true);
        settings.set(preview, RenderSettings.defaults());
    }

    @Test
    void biomeCellsRoundTripViaWriteBinaryReadBinary() throws IOException {
        final int yMin = -64;
        final int yMax = 320;
        PreviewStorage original = new PreviewStorage(yMin, yMax);

        PreviewSection section = original.section4(0, 0, 0, PreviewStorage.FLAG_BIOME);
        section.set(0, 0, (short) 42);
        section.set(1, 2, (short) 7);
        section.set(10, 10, (short) 99);

        PreviewSection height = original.section4(0, 0, 0, PreviewStorage.FLAG_HEIGHT);
        height.set(0, 0, (short) 64);
        height.set(3, 5, (short) 120);

        // quartY=4 -> block Y layer; local (0,0) is absolute quart (0,0) after section origin mask
        PreviewSection intersect = original.section4(0, 4, 0, PreviewStorage.FLAG_INTERSECT);
        intersect.set(0, 0, (short) 1);

        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            original.writeBinary(dos);
            bytes = baos.toByteArray();
        }

        PreviewStorage restored;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            restored = PreviewStorage.readBinary(dis, yMin, yMax);
        }

        assertEquals((short) 42, restored.getRawData4(0, 0, 0, PreviewStorage.FLAG_BIOME));
        assertEquals((short) 7, restored.getRawData4(1, 0, 2, PreviewStorage.FLAG_BIOME));
        assertEquals((short) 99, restored.getRawData4(10, 0, 10, PreviewStorage.FLAG_BIOME));
        assertEquals((short) 64, restored.getRawData4(0, 0, 0, PreviewStorage.FLAG_HEIGHT));
        assertEquals((short) 120, restored.getRawData4(3, 0, 5, PreviewStorage.FLAG_HEIGHT));
        assertEquals((short) 1, restored.getRawData4(0, 4, 0, PreviewStorage.FLAG_INTERSECT));
        assertEquals(Short.MIN_VALUE, restored.getRawData4(5, 0, 5, PreviewStorage.FLAG_BIOME));
    }

    @Test
    void halfAndQuarterSectionsRoundTrip() throws IOException {
        PreviewSectionHalf half = new PreviewSectionHalf(0, 0);
        half.set(0, 0, (short) 11);
        half.set(2, 4, (short) 22);

        PreviewSectionQuarter quarter = new PreviewSectionQuarter(64, 0);
        quarter.set(0, 0, (short) 33);
        quarter.set(4, 4, (short) 44);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            half.writeBinary(dos);
            quarter.writeBinary(dos);
            byte[] bytes = baos.toByteArray();
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
                PreviewSection half2 = PreviewSection.readBinary(dis);
                PreviewSection quarter2 = PreviewSection.readBinary(dis);
                assertEquals((short) 11, half2.get(0, 0));
                assertEquals((short) 22, half2.get(2, 4));
                assertEquals((short) 33, quarter2.get(0, 0));
                assertEquals((short) 44, quarter2.get(4, 4));
                assertEquals(PreviewSection.HALF_SIZE, half2.size());
                assertEquals(PreviewSection.SECTION_SIZE, quarter2.size());
            }
        }
    }

    @Test
    void structureSectionRoundTrip() throws IOException {
        PreviewSectionStructure section = new PreviewSectionStructure(0, 0);
        section.addStructure(new PreviewSection.PreviewStruct(
                new BlockPos(10, 64, 20),
                (short) 5,
                new BoundingBox(8, 60, 18, 12, 70, 22)
        ));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            section.writeBinary(dos);
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
                PreviewSection restored = PreviewSection.readBinary(dis);
                assertEquals(1, restored.structures().size());
                PreviewSection.PreviewStruct s = restored.structures().get(0);
                assertEquals(10, s.center().getX());
                assertEquals(64, s.center().getY());
                assertEquals(20, s.center().getZ());
                assertEquals(5, s.structureId());
                assertEquals(8, s.boundingBox().minX());
                assertEquals(70, s.boundingBox().maxY());
            }
        }
    }

    @Test
    void badMagicThrows() {
        byte[] bad = new byte[]{0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 64, 0, 0, 0, 0};
        assertThrows(IOException.class, () -> {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bad))) {
                PreviewStorage.readBinary(dis, 0, 64);
            }
        });
    }

    @Test
    void yRangeMismatchThrows() throws IOException {
        PreviewStorage original = new PreviewStorage(0, 64);
        original.section4(0, 0, 0, PreviewStorage.FLAG_BIOME).set(0, 0, (short) 1);

        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            original.writeBinary(dos);
            bytes = baos.toByteArray();
        }

        assertThrows(IOException.class, () -> {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
                PreviewStorage.readBinary(dis, -64, 320);
            }
        });
    }
}
