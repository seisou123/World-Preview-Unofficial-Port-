package caeruleusTait.world.preview.viewmodel;

import caeruleusTait.world.preview.domain.preview.Preview;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the viewmodel layer.
 */
class ViewModelTest {

    // ---- PreviewViewModel tests ----

    @Test
    void previewViewModelInitialState() {
        PreviewViewModel vm = new PreviewViewModel();
        assertEquals(PreviewViewModel.State.IDLE, vm.state());
        assertNull(vm.currentPreview());
        assertNull(vm.errorMessage());
        assertEquals(0.0f, vm.progress());
        assertEquals(0, vm.sampledChunks());
        assertEquals(0, vm.totalChunks());
    }

    @Test
    void previewViewModelSetState() {
        PreviewViewModel vm = new PreviewViewModel();
        vm.setState(PreviewViewModel.State.GENERATING);
        assertEquals(PreviewViewModel.State.GENERATING, vm.state());
    }

    @Test
    void previewViewModelSetError() {
        PreviewViewModel vm = new PreviewViewModel();
        vm.setError("Something went wrong");
        assertEquals(PreviewViewModel.State.ERROR, vm.state());
        assertEquals("Something went wrong", vm.errorMessage());
    }

    @Test
    void previewViewModelClearError() {
        PreviewViewModel vm = new PreviewViewModel();
        vm.setError("Error");
        vm.clearError();
        assertEquals(PreviewViewModel.State.IDLE, vm.state());
        assertNull(vm.errorMessage());
    }

    @Test
    void previewViewModelSetProgress() {
        PreviewViewModel vm = new PreviewViewModel();
        vm.setProgress(0.5f);
        assertEquals(0.5f, vm.progress());
    }

    @Test
    void previewViewModelProgressClamped() {
        PreviewViewModel vm = new PreviewViewModel();
        vm.setProgress(-1.0f);
        assertEquals(0.0f, vm.progress());
        vm.setProgress(2.0f);
        assertEquals(1.0f, vm.progress());
    }

    @Test
    void previewViewModelSetChunkProgress() {
        PreviewViewModel vm = new PreviewViewModel();
        vm.setChunkProgress(50, 200);
        assertEquals(50, vm.sampledChunks());
        assertEquals(200, vm.totalChunks());
        assertEquals(0.25f, vm.progress());
    }

    @Test
    void previewViewModelSetCurrentPreview() {
        PreviewViewModel vm = new PreviewViewModel();
        Preview preview = new Preview() {
            @Override public long seed() { return 12345L; }
            @Override public String dimension() { return "minecraft:overworld"; }
            @Override public int pixelsPerChunk() { return 16; }
            @Override public int minX() { return 0; }
            @Override public int minZ() { return 0; }
            @Override public int maxX() { return 0; }
            @Override public int maxZ() { return 0; }
            @Override public int y() { return 64; }
        };

        vm.setCurrentPreview(preview);
        assertSame(preview, vm.currentPreview());
    }

    @Test
    void previewViewModelListenerNotified() {
        PreviewViewModel vm = new PreviewViewModel();
        AtomicReference<PreviewViewModel> received = new AtomicReference<>();
        vm.addListener(received::set);

        vm.setState(PreviewViewModel.State.GENERATING);
        assertSame(vm, received.get());
    }

    @Test
    void previewViewModelRemoveListener() {
        PreviewViewModel vm = new PreviewViewModel();
        AtomicReference<PreviewViewModel> received = new AtomicReference<>();
        PreviewViewModel.StateListener listener = received::set;
        vm.addListener(listener);

        vm.setState(PreviewViewModel.State.GENERATING);
        assertNotNull(received.get());

        received.set(null);
        vm.removeListener(listener);
        vm.setState(PreviewViewModel.State.READY);
        assertNull(received.get());
    }

    @Test
    void previewViewModelReset() {
        PreviewViewModel vm = new PreviewViewModel();
        vm.setError("Error");
        vm.setProgress(0.5f);
        vm.setChunkProgress(10, 20);
        vm.setState(PreviewViewModel.State.GENERATING);

        vm.reset();

        assertEquals(PreviewViewModel.State.IDLE, vm.state());
        assertNull(vm.errorMessage());
        assertEquals(0.0f, vm.progress());
        assertEquals(0, vm.sampledChunks());
        assertEquals(0, vm.totalChunks());
    }

    @Test
    void previewViewModelListenerExceptionDoesNotPropagate() {
        PreviewViewModel vm = new PreviewViewModel();
        vm.addListener(vm2 -> { throw new RuntimeException("Listener error"); });

        // Should not throw
        assertDoesNotThrow(() -> vm.setState(PreviewViewModel.State.GENERATING));
    }

    // ---- AnalysisViewModel tests ----

    @Test
    void analysisViewModelInitialState() {
        AnalysisViewModel vm = new AnalysisViewModel();
        assertEquals(AnalysisViewModel.AnalysisState.IDLE, vm.state());
        assertNull(vm.errorMessage());
        assertNull(vm.taskId());
        assertEquals(0, vm.analyzedChunks());
        assertEquals(0, vm.totalChunks());
        assertEquals(0.0f, vm.progress());
        assertNull(vm.lastMetrics());

        AnalysisViewModel.RegionSelection region = vm.region();
        assertEquals(0, region.minX());
        assertEquals(0, region.minZ());
        assertEquals(16, region.maxX());
        assertEquals(16, region.maxZ());
    }

    @Test
    void analysisViewModelSetState() {
        AnalysisViewModel vm = new AnalysisViewModel();
        vm.setState(AnalysisViewModel.AnalysisState.ANALYZING);
        assertEquals(AnalysisViewModel.AnalysisState.ANALYZING, vm.state());
    }

    @Test
    void analysisViewModelSetError() {
        AnalysisViewModel vm = new AnalysisViewModel();
        vm.setError("Analysis failed");
        assertEquals(AnalysisViewModel.AnalysisState.ERROR, vm.state());
        assertEquals("Analysis failed", vm.errorMessage());
    }

    @Test
    void analysisViewModelSetRegion() {
        AnalysisViewModel vm = new AnalysisViewModel();
        AnalysisViewModel.RegionSelection region = new AnalysisViewModel.RegionSelection(-32, -32, 32, 32);
        vm.setRegion(region);
        assertSame(region, vm.region());
        assertEquals(65, region.width());
        assertEquals(65, region.height());
    }

    @Test
    void analysisViewModelRegionValidation() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisViewModel.RegionSelection(10, 0, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisViewModel.RegionSelection(0, 10, 0, 5));
    }

    @Test
    void analysisViewModelRegionChunkCount() {
        AnalysisViewModel.RegionSelection region = new AnalysisViewModel.RegionSelection(0, 0, 15, 15);
        assertEquals(1, region.chunkCount());

        region = new AnalysisViewModel.RegionSelection(0, 0, 31, 31);
        assertEquals(4, region.chunkCount());

        region = new AnalysisViewModel.RegionSelection(0, 0, 47, 47);
        assertEquals(9, region.chunkCount());
    }

    @Test
    void analysisViewModelSetProgress() {
        AnalysisViewModel vm = new AnalysisViewModel();
        vm.setProgress(100, 400, 0.25f);
        assertEquals(100, vm.analyzedChunks());
        assertEquals(400, vm.totalChunks());
        assertEquals(0.25f, vm.progress());
    }

    @Test
    void analysisViewModelSetMetrics() {
        AnalysisViewModel vm = new AnalysisViewModel();
        AnalysisViewModel.MetricsSnapshot metrics = new AnalysisViewModel.MetricsSnapshot(
                64.5, 64.0, 32, 128, 5, 2
        );
        vm.setLastMetrics(metrics);
        assertSame(metrics, vm.lastMetrics());
        assertEquals(64.5, metrics.meanHeight());
        assertEquals(64.0, metrics.medianHeight());
        assertEquals(32, metrics.minHeight());
        assertEquals(128, metrics.maxHeight());
        assertEquals(5, metrics.biomeCount());
        assertEquals(2, metrics.structureCount());
    }

    @Test
    void analysisViewModelReset() {
        AnalysisViewModel vm = new AnalysisViewModel();
        vm.setState(AnalysisViewModel.AnalysisState.ANALYZING);
        vm.setError("Error");
        vm.setProgress(50, 100, 0.5f);
        vm.setLastMetrics(new AnalysisViewModel.MetricsSnapshot(64, 64, 32, 128, 3, 1));

        vm.reset();

        assertEquals(AnalysisViewModel.AnalysisState.IDLE, vm.state());
        assertNull(vm.errorMessage());
        assertEquals(0, vm.analyzedChunks());
        assertEquals(0, vm.totalChunks());
        assertEquals(0.0f, vm.progress());
        assertNull(vm.lastMetrics());
    }

    @Test
    void analysisViewModelListenerNotified() {
        AnalysisViewModel vm = new AnalysisViewModel();
        AtomicReference<AnalysisViewModel> received = new AtomicReference<>();
        vm.addListener(received::set);

        vm.setState(AnalysisViewModel.AnalysisState.ANALYZING);
        assertSame(vm, received.get());
    }

    // ---- SettingsViewModel tests ----

    @Test
    void settingsViewModelInitialState() {
        SettingsViewModel vm = new SettingsViewModel();
        assertFalse(vm.dirty());
        assertTrue(vm.valid());
        assertNull(vm.validationError());
        assertEquals("general", vm.activePage());
    }

    @Test
    void settingsViewModelSetDirty() {
        SettingsViewModel vm = new SettingsViewModel();
        vm.setDirty(true);
        assertTrue(vm.dirty());
    }

    @Test
    void settingsViewModelSetValid() {
        SettingsViewModel vm = new SettingsViewModel();
        vm.setValid(false, "Invalid value");
        assertFalse(vm.valid());
        assertEquals("Invalid value", vm.validationError());

        vm.setValid(true, null);
        assertTrue(vm.valid());
        assertNull(vm.validationError());
    }

    @Test
    void settingsViewModelSetActivePage() {
        SettingsViewModel vm = new SettingsViewModel();
        vm.setActivePage("cache");
        assertEquals("cache", vm.activePage());
    }

    @Test
    void settingsViewModelReset() {
        SettingsViewModel vm = new SettingsViewModel();
        vm.setDirty(true);
        vm.setValid(false, "Error");
        vm.setActivePage("heightmap");

        vm.reset();

        assertFalse(vm.dirty());
        assertTrue(vm.valid());
        assertNull(vm.validationError());
        assertEquals("general", vm.activePage());
    }

    @Test
    void settingsViewModelListenerNotified() {
        SettingsViewModel vm = new SettingsViewModel();
        AtomicReference<SettingsViewModel> received = new AtomicReference<>();
        vm.addListener(received::set);

        vm.setActivePage("dimension");
        assertSame(vm, received.get());
    }
}
