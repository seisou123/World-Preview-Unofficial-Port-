// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.stubs;

import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * Minimal stub implementation of ServerLevelData for preview generation.
 *
 * <p>In MC 26.2 the LevelData/ServerLevelData interfaces were significantly
 * simplified — weather, wandering-trader, game-rule, scheduled-event and
 * world-border methods were removed from the level-data hierarchy.  This
 * stub implements only the remaining abstract methods.
 */
public class DummyServerLevelData implements ServerLevelData {

    // --- ServerLevelData methods ---

    @Override
    public String getLevelName() {
        return "dummy";
    }

    @Override
    public GameType getGameType() {
        return GameType.SPECTATOR;
    }

    @Override
    public boolean isInitialized() {
        return false;
    }

    @Override
    public void setInitialized(boolean initialized) {
    }

    @Override
    public boolean isAllowCommands() {
        return false;
    }

    @Override
    public void setAllowCommands(boolean allow) {
    }

    @Override
    public void setGameType(GameType type) {
    }

    @Override
    public void setGameTime(long time) {
    }

    @Override
    public void setDayTimePerTick(float perTick) {
    }

    @Override
    public void setDayTimeFraction(float fraction) {
    }

    @Override
    public float getDayTimePerTick() {
        return 1.0f;
    }

    @Override
    public float getDayTimeFraction() {
        return 0.0f;
    }

    // --- LevelData methods ---

    @Override
    public LevelData.RespawnData getRespawnData() {
        return LevelData.RespawnData.DEFAULT;
    }

    @Override
    public long getGameTime() {
        return 0;
    }

    @Override
    public boolean isHardcore() {
        return false;
    }

    @Override
    public Difficulty getDifficulty() {
        return Difficulty.HARD;
    }

    @Override
    public boolean isDifficultyLocked() {
        return false;
    }

    // --- WritableLevelData methods ---

    @Override
    public void setSpawn(LevelData.RespawnData respawnData) {
    }
}
