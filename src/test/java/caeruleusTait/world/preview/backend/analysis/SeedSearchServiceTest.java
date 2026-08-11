package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试 SeedSearchService 的核心逻辑。
 * 通过 SeedSearchService.SeedContextFactory 和 BiomeSampler 接口注入 fake 实现，
 * 不依赖真实 Minecraft 世界生成。
 */
class SeedSearchServiceTest {

    private SeedSearchService service;

    @BeforeEach
    void setUp() {
        service = new SeedSearchService(null, 1);
    }

    @Test
    @DisplayName("命中即停：首个种子命中后停止搜索")
    void hitStopsFurtherSearch() throws Exception {
        var request = new SeedSearchRequest(
                Identifier.parse("minecraft:plains"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                0, 16, 0, 16, 16, "test", 100, 0, 0
        );

        var resultRef = new AtomicReference<SeedSearchResult>();
        // 所有种子都返回目标群系，因此第一个随机种子即命中
        var factory = new FakeSamplerFactory(seed -> "minecraft:plains");

        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(300);

        assertNotNull(resultRef.get());
        assertInstanceOf(SeedSearchResult.Hit.class, resultRef.get());
        // 种子可以是任意 long 值（正数或负数）
        assertNotEquals(0, ((SeedSearchResult.Hit) resultRef.get()).seed());
    }

    @Test
    @DisplayName("达到最大尝试次数后报告 Miss")
    void missAfterMaxAttempts() throws Exception {
        var request = new SeedSearchRequest(
                Identifier.parse("minecraft:rare_biome"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                0, 16, 0, 16, 16, "test", 10, 0, 0
        );

        var resultRef = new AtomicReference<SeedSearchResult>();
        var factory = new FakeSamplerFactory(seed -> "minecraft:desert");

        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(500);

        assertNotNull(resultRef.get());
        assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
    }

    @Test
    @DisplayName("取消后停止且不注入")
    void cancelStopsSearch() throws Exception {
        var request = new SeedSearchRequest(
                Identifier.parse("minecraft:rare_biome"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                0, 16, 0, 16, 16, "test", 100, 0, 0
        );

        var resultRef = new AtomicReference<SeedSearchResult>();
        var hitRef = new AtomicLong(-1);
        var factory = new FakeSamplerFactory(seed -> {
            service.cancel();
            return "minecraft:desert";
        });

        service.startSearch(request, factory, seed -> hitRef.set(seed), result -> resultRef.set(result), attempts -> {});
        Thread.sleep(300);

        assertNotNull(resultRef.get());
        assertInstanceOf(SeedSearchResult.Cancelled.class, resultRef.get());
        assertEquals(-1, hitRef.get());
    }

    @Test
    @DisplayName("重复请求被拒绝")
    void rejectsDuplicateRequest() throws Exception {
        var request = new SeedSearchRequest(
                Identifier.parse("minecraft:plains"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                0, 16, 0, 16, 16, "test", 100, 0, 0
        );

        var factory = new FakeSamplerFactory(seed -> "minecraft:desert");

        assertTrue(service.startSearch(request, factory, seed -> {}, result -> {}, attempts -> {}));
        assertFalse(service.startSearch(request, factory, seed -> {}, result -> {}, attempts -> {}));
    }

    @Test
    @DisplayName("连续失败超过阈值后提前终止")
    void consecutiveFailuresEarlyAbort() throws Exception {
        var request = new SeedSearchRequest(
                Identifier.parse("minecraft:rare_biome"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                0, 16, 0, 16, 16, "test", 100, 0, 0
        );

        var resultRef = new AtomicReference<SeedSearchResult>();
        var factory = new FailingSamplerFactory();

        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(1000);

        assertNotNull(resultRef.get());
        assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
        // 应在 10 次连续失败后提前终止
        assertTrue(factory.callCount.get() >= 10 && factory.callCount.get() <= 15);
    }

    @Test
    @DisplayName("BiomeSampler.close() 被调用")
    void samplerCloseIsCalled() throws Exception {
        var request = new SeedSearchRequest(
                Identifier.parse("minecraft:rare_biome"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                0, 16, 0, 16, 16, "test", 3, 0, 0
        );

        var resultRef = new AtomicReference<SeedSearchResult>();
        var factory = new CloseTrackingSamplerFactory();

        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(500);

        assertNotNull(resultRef.get());
        // 每个候选种子都应关闭其采样器
        assertTrue(factory.closeCount.get() >= 1, "BiomeSampler.close() 应被调用");
    }

    // ========== 测试辅助 ==========

    /** 仿真的种子上下文工厂，通过 BiomeSampler 接口注入 fake 采样结果 */
    private static class FakeSamplerFactory implements SeedSearchService.SeedContextFactory {
        private final java.util.function.LongFunction<String> biomeFn;
        FakeSamplerFactory(java.util.function.LongFunction<String> biomeFn) {
            this.biomeFn = biomeFn;
        }
        @Override
        public SeedSearchService.BiomeSampler createSampler(long seed) {
            var targetBiome = Identifier.parse(biomeFn.apply(seed));
            return (x, y, z, target) -> target.equals(targetBiome);
        }
    }

    // 辅助类：始终抛异常的工厂
    private static class FailingSamplerFactory implements SeedSearchService.SeedContextFactory {
        final AtomicInteger callCount = new AtomicInteger(0);
        @Override
        public SeedSearchService.BiomeSampler createSampler(long seed) {
            callCount.incrementAndGet();
            return (x, y, z, target) -> { throw new RuntimeException("simulated error"); };
        }
    }

    // 辅助类：跟踪 close() 调用的工厂
    private static class CloseTrackingSamplerFactory implements SeedSearchService.SeedContextFactory {
        final AtomicInteger closeCount = new AtomicInteger(0);
        @Override
        public SeedSearchService.BiomeSampler createSampler(long seed) {
            return new SeedSearchService.BiomeSampler() {
                @Override
                public boolean sampleContains(int x, int y, int z, Identifier target) {
                    return false; // 永远不命中
                }
                @Override
                public void close() {
                    closeCount.incrementAndGet();
                }
            };
        }
    }

    @Test
    @DisplayName("种子在一次搜索内不重复")
    void seedsAreUniqueWithinSearch() throws Exception {
        var request = new SeedSearchRequest(
                Identifier.parse("minecraft:rare_biome"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                0, 16, 0, 16, 16, "test", 20, 0, 0
        );

        var seedsSeen = new java.util.concurrent.ConcurrentHashMap<Long, Boolean>();
        var factory = new SeedSearchService.SeedContextFactory() {
            @Override
            public SeedSearchService.BiomeSampler createSampler(long seed) {
                seedsSeen.put(seed, true);
                return (x, y, z, target) -> false; // never match
            }
        };

        var resultRef = new AtomicReference<SeedSearchResult>();
        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(1000);

        assertNotNull(resultRef.get());
        // 每个种子只出现一次（Set 去重后 size == 种子数）
        assertEquals(20, seedsSeen.size());
    }

    @Test
    @DisplayName("SeedSearchRequest 拒绝无效视口边界")
    void requestRejectsInvalidViewport() {
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                Identifier.parse("minecraft:plains"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                100, -100, -100, 100, 4, "test", 100, 0, 0 // viewMinX > viewMaxX
        ));
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                Identifier.parse("minecraft:plains"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                -100, 100, 100, -100, 4, "test", 100, 0, 0 // viewMinZ > viewMaxZ
        ));
    }
}
