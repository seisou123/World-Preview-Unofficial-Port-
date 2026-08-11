// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import org.apache.commons.lang3.NotImplementedException;

import java.util.Arrays;
import java.util.List;

public abstract class PreviewSectionCompressed extends PreviewSection {

    private final int size;

    /**
     * Immutable snapshot of the compressed arrays. Published via a single volatile write so
     * concurrent readers always observe a matching mapData/data pair.
     */
        private static final class CompactState {

        final short[] mapData;
        final short[] data;

        CompactState(short[] mapData, short[] data) {
            this.mapData = mapData;
            this.data = data;
        }
    }

    private volatile CompactState state;

    private short lastIdx = 0;

    public PreviewSectionCompressed(int quartX, int quartZ, int size) {
        super(quartX, quartZ);
        this.size = size;
        this.state = new CompactState(new short[0], new short[]{Short.MIN_VALUE});
    }

    //   ________  _________ _
    //  |_   _|  \/  || ___ \ |
    //    | | | .  . || |_/ / |
    //    | | | |\/| ||  __/| |
    //   _| |_| |  | || |   | |____
    //   \___/\_|  |_/\_|   \_____/
    //

    public static class Full extends PreviewSectionCompressed {
        public Full(int quartX, int quartZ) {
            super(quartX, quartZ, SIZE);
        }

        @Override
        public int xzToIdx(int x, int z) {
            return x * SIZE + z;
        }
    }

    public static class Half extends PreviewSectionCompressed {
        public Half(int quartX, int quartZ) {
            super(quartX, quartZ, HALF_SIZE);
        }

        @Override
        public int xzToIdx(int x, int z) {
            return (x >> HALF_SHIFT) * HALF_SIZE + (z >> HALF_SHIFT);
        }
    }

    public static class Quarter extends PreviewSectionCompressed {
        public Quarter(int quartX, int quartZ) {
            super(quartX, quartZ, SECTION_SIZE);
        }

        @Override
        public int xzToIdx(int x, int z) {
            return (x >> QUART_TO_SECTION_SHIFT) * SECTION_SIZE + (z >> QUART_TO_SECTION_SHIFT);
        }
    }

    //   _     _____ _____ _____ _____
    //  | |   |  _  |  __ \_   _/  __ \
    //  | |   | | | | |  \/ | | | /  \/
    //  | |   | | | | | __  | | | |
    //  | |___\ \_/ / |_\ \_| |_| \__/\
    //  \_____/\___/ \____/\___/ \____/
    //

    public abstract int xzToIdx(int x, int z);

    public short get(int x, int z) {
        final int idx = xzToIdx(x, z);
        // Using synchronized is expensive. Solution: Only require eventual correctness for
        // reading and publish compression upgrades atomically via CompactState so readers never
        // observe a mismatched mapData/data pair.
        //
        // If still something goes wrong, catch the IndexOutOfBoundsException and return MIN_VALUE.
        try {
            return getReal(state, idx);
        } catch (IndexOutOfBoundsException e) {
            return Short.MIN_VALUE;
        }
    }

    private short getReal(CompactState s, int idx) {
        return switch (s.mapData.length) {
            // The entire section only contains one single value
            case 0 -> s.data[0];

            // There is no cache (magic array length 1)
            case 1 -> s.data[idx];

            // First compression level (oct - 4 unique values | 2 bit per value)
            case 4 -> {
                final short word = s.data[idx >> 3];
                final int map_idx = (word >> ((idx & 0b111) << 1)) & 0b11;
                yield s.mapData[map_idx];
            }

            // Second compression level (quart - 16 unique values | 4 bit per value)
            case 16 -> {
                final short word = s.data[idx >> 2];
                final int map_idx = (word >> ((idx & 0b11) << 2)) & 0b1111;
                yield s.mapData[map_idx];
            }

            // Third compression level (quart - 256 unique values | 8 bit per value)
            case 256 -> {
                final short word = s.data[idx >> 1];
                final int map_idx = (word >> ((idx & 0b1) << 3)) & 0b11111111;
                yield s.mapData[map_idx];
            }
            default -> throw new IllegalStateException("Unexpected value: " + s.mapData.length);
        };
    }


    private void internalSetData(CompactState s, int x, int z, short value) {
        final int idx = xzToIdx(x, z);
        switch (s.mapData.length) {
            // The entire section only contains one single value
            case 0 -> s.data[0] = value;

            // There is no cache (magic array length 1)
            case 1 -> s.data[idx] = value;

            // First compression level (oct - 4 unique values | 2 bit per value)
            case 4 -> {
                final int didx = idx >> 3;
                final int shift = (idx & 0b111) << 1;
                final int mask = ~(0b11 << shift);
                s.data[didx] = (short) ((s.data[didx] & mask) | (value & 0b11) << shift);
            }

            // Second compression level (quart - 16 unique values | 4 bit per value)
            case 16 -> {
                final int didx = idx >> 2;
                final int shift = (idx & 0b11) << 2;
                final int mask = ~(0b1111 << shift);
                s.data[didx] = (short) ((s.data[didx] & mask) | (value & 0b1111) << shift);
            }

            // Third compression level (quart - 256 unique values | 8 bit per value)
            case 256 -> {
                final int didx = idx >> 1;
                final int shift = (idx & 0b1) << 3;
                final int mask = ~(0b11111111 << shift);
                s.data[didx] = (short) ((s.data[didx] & mask) | (value & 0b11111111) << shift);
            }
            default -> throw new IllegalStateException("Unexpected value: " + s.mapData.length);
        }
    }

    /**
     * Calculates the mapData index for a specific value. If the value
     * is not already present, the new value is appended to the map
     * <p>
     * If the mapData is already full, the compression will be migrated
     * to the next level and a new CompactState is published.
     *
     * @return the map index, or the raw value when expanding to no compression
     */
    private short cacheMapIdx(CompactState s, short value) {
        // Check cache
        if (s.mapData[lastIdx] == value) {
            return lastIdx;
        }

        // Find or insert in existing map
        for (short i = 0; i < s.mapData.length; ++i) {
            if (value == s.mapData[i]) {
                return lastIdx = i;
            } else if (s.mapData[i] == Short.MIN_VALUE) {
                s.mapData[i] = value;
                return lastIdx = i;
            }
        }

        // We need to grow the array (expensive) — publish mapData + data together
        return switch (s.mapData.length) {
            // Grow first level compression to second level compression
            case 4 -> {
                // Grow mapData
                short[] newMapData = Arrays.copyOf(s.mapData, 16);
                newMapData[4] = value;
                Arrays.fill(newMapData, 5, 16, Short.MIN_VALUE);

                // Grow data
                short[] newData = new short[s.data.length * 2];
                for (int i = 0; i < s.data.length; ++i) {
                    final short word = s.data[i];
                    newData[i * 2 + 0] = (short) ((((word >> 0) & 0b11) << 0) | (((word >>  2) & 0b11) << 4) | (((word >>  4) & 0b11) << 8) | (((word >>  6) & 0b11) << 12));
                    newData[i * 2 + 1] = (short) ((((word >> 8) & 0b11) << 0) | (((word >> 10) & 0b11) << 4) | (((word >> 12) & 0b11) << 8) | (((word >> 14) & 0b11) << 12));
                }

                this.state = new CompactState(newMapData, newData);
                yield 4;
            }

            // Grow second level compression to third level compression
            case 16 -> {
                // Grow mapData
                short[] newMapData = Arrays.copyOf(s.mapData, 256);
                newMapData[16] = value;
                Arrays.fill(newMapData, 17, 256, Short.MIN_VALUE);

                // Grow data
                short[] newData = new short[s.data.length * 2];
                for (int i = 0; i < s.data.length; ++i) {
                    final short word = s.data[i];
                    newData[i * 2 + 0] = (short) ((((word >> 0) & 0b1111) << 0) | (((word >>  4) & 0b1111) << 8));
                    newData[i * 2 + 1] = (short) ((((word >> 8) & 0b1111) << 0) | (((word >> 12) & 0b1111) << 8));
                }
                this.state = new CompactState(newMapData, newData);
                yield 16;
            }

            // Fully expand third level to no compression
            case 256 -> {
                // Grow data
                short[] newData = new short[s.data.length * 2];
                for (int i = 0; i < s.data.length; ++i) {
                    final short word = s.data[i];
                    newData[i * 2 + 0] = s.mapData[((word >> 0) & 0b11111111)];
                    newData[i * 2 + 1] = s.mapData[((word >> 8) & 0b11111111)];
                }

                // There is no cache (magic array length 1)
                this.state = new CompactState(new short[1], newData);

                // No more compression --> no map --> no index, just the raw value
                yield value;
            }
            default -> throw new IllegalStateException("Unexpected value: " + s.mapData.length);
        };
    }


    public synchronized void set(int x, int z, short biome) {
        CompactState s = state;
        if (s.mapData.length == 0) {
            // Handle single value for entire section

            if (s.data[0] == biome) {
                // Nothing to do
            } else if (s.data[0] == Short.MIN_VALUE) {
                s.data[0] = biome;
            } else {
                // new value --> expand to first level compression
                short[] newData = new short[(size * size) >> 3];
                Arrays.fill(newData, (short) 0);
                CompactState expanded = new CompactState(
                        new short[]{s.data[0], biome, Short.MIN_VALUE, Short.MIN_VALUE},
                        newData
                );
                this.state = expanded;
                internalSetData(expanded, x, z, (short) 1);
            }
        } else if (s.mapData.length == 1) {
            // Handle no compression

            s.data[xzToIdx(x, z)] = biome;
        } else {
            // Some level of compression
            short mapIdx = cacheMapIdx(s, biome);
            // cacheMapIdx may have published a new state on upgrade — re-read for write
            CompactState current = state;
            internalSetData(current, x, z, mapIdx);
        }
    }

    //   _____ _   _ ______ _____
    //  |_   _| \ | ||  ___|  _  |
    //    | | |  \| || |_  | | | |
    //    | | | . ` ||  _| | | | |
    //   _| |_| |\  || |   \ \_/ /
    //   \___/\_| \_/\_|    \___/
    //

    @Override
    public int size() {
        return size;
    }

    @Override
    public List<PreviewStruct> structures() {
        throw new NotImplementedException();
    }

    @Override
    public void addStructure(PreviewStruct structureData) {
        throw new NotImplementedException();
    }

    public synchronized short mapSize() {
        short[] mapData = state.mapData;
        short s;

        for (s = 0; s < mapData.length; s++) {
            if (mapData[s] == Short.MIN_VALUE) {
                return s;
            }
        }

        return s;
    }
}
