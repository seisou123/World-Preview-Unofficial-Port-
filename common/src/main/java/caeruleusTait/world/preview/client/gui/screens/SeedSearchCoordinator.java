// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.analysis.SeedSearchRequest;
import caeruleusTait.world.preview.backend.analysis.SeedSearchResult;
import caeruleusTait.world.preview.backend.analysis.SeedSearchService;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Seed-search orchestration owned by the {@link PreviewContainer}: the
 * search service, the per-seed sampler factory captured at worldgen setup,
 * and the last completed result (plus its criteria label) so a reopened
 * screen can redisplay it.
 */
final class SeedSearchCoordinator {
    private final SeedSearchService service;
    private volatile SeedSearchService.SeedContextFactory factory;
    // Seed search listener indirection: a search started on one screen keeps
    // running in the background, and a reopened screen can take over the
    // progress/completion callbacks. Callbacks always fire on the main thread
    // (via minecraftExecute), so reassigning the listeners is race-free.
    private volatile Consumer<SeedSearchResult> completeListener = r -> { };
    private volatile Consumer<Integer> progressListener = a -> { };
    @Nullable private volatile SeedSearchResult lastResult;
    @Nullable private volatile String lastCriteria;

    SeedSearchCoordinator(Minecraft minecraft, int threadCount) {
        this.service = new SeedSearchService(minecraft, threadCount);
    }

    boolean isRunning() {
        return service.isSearching();
    }

    void cancel() {
        service.cancel();
    }

    void close() {
        service.close();
    }

    void setFactory(SeedSearchService.SeedContextFactory factory) {
        this.factory = factory;
    }

    void clearFactory() {
        factory = null;
    }

    SeedSearchService.SeedContextFactory factory() {
        return factory;
    }

    /**
     * Starts an advanced (multi-criteria) seed search. The search runs in the
     * background; its callbacks can be re-targeted to another screen later
     * via {@link #reattach(Consumer, Consumer)}.
     *
     * @param criteriaLabel label stored alongside the latest result so a
     *                      reopened screen can display it
     * @return false when the search context is missing or a search is running
     */
    boolean start(SeedSearchRequest request, String criteriaLabel,
                  Consumer<SeedSearchResult> onComplete, Consumer<Integer> onProgress) {
        if (factory == null) {
            LOGGER.warn("Search context not available");
            return false;
        }
        boolean started = service.startSearch(request, factory,
                null, result -> completeListener.accept(result), attempts -> progressListener.accept(attempts));
        if (started) {
            // Only after a successful start: discard the previous result and
            // route the running search's callbacks through the new listeners,
            // recording the outcome for later reopens.
            lastResult = null;
            lastCriteria = null;
            completeListener = result -> {
                lastResult = result;
                lastCriteria = criteriaLabel;
                onComplete.accept(result);
            };
            progressListener = onProgress;
        }
        return started;
    }

    /** Makes the given listener the target of the running search's progress/completion callbacks. */
    void reattach(Consumer<SeedSearchResult> onComplete, Consumer<Integer> onProgress) {
        completeListener = result -> {
            lastResult = result;
            onComplete.accept(result);
        };
        progressListener = onProgress;
    }

    /** The most recent completed seed search result, or null before the first completion. */
    @Nullable
    SeedSearchResult lastResult() {
        return lastResult;
    }

    /** The criteria label stored alongside {@link #lastResult()}, or null. */
    @Nullable
    String lastCriteria() {
        return lastCriteria;
    }
}
