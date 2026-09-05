package caeruleusTait.world.preview.backend.storage;

import org.apache.commons.lang3.NotImplementedException;

import java.util.ArrayList;
import java.util.List;

public class PreviewSectionStructure extends PreviewSection {

    // (R3) Copy-on-write: structures() is called every frame by the render
    // loop and used to allocate a fresh ArrayList copy per call.  The current
    // snapshot is now shared directly (volatile for cross-thread visibility);
    // addStructure publishes a new immutable snapshot under the section
    // monitor instead of mutating in place.
    private volatile List<PreviewStruct> structures = List.of();

    public PreviewSectionStructure(int quartX, int quartZ) {
        super(quartX, quartZ);
    }

    @Override
    public List<PreviewStruct> structures() {
        return structures;
    }

    @Override
    public synchronized void addStructure(PreviewStruct structureData) {
        // (R3) Copy-on-write: build the appended list and publish it as an
        // immutable snapshot; concurrent readers keep iterating the previous
        // snapshot without locking or copying.
        final List<PreviewStruct> next = new ArrayList<>(structures);
        next.add(structureData);
        structures = List.copyOf(next);
    }

    @Override
    public short get(int x, int z) {
        throw new NotImplementedException();
    }

    @Override
    public void set(int x, int z, short biome) {
        throw new NotImplementedException();
    }

    @Override
    public int size() {
        return structures.size();
    }
}
