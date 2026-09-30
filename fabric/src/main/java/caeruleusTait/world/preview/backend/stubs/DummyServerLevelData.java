// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.stubs;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.timers.TimerQueue;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public class DummyServerLevelData implements ServerLevelData {
    @Override
    public String getLevelName() {
        return "dummy";
    }

    @Override
    public void setThundering(boolean thundering) {

    }

    @Override
    public int getRainTime() {
        return 0;
    }

    @Override
    public void setRainTime(int time) {

    }

    @Override
    public void setThunderTime(int time) {

    }

    @Override
    public int getThunderTime() {
        return 0;
    }

    @Override
    public int getClearWeatherTime() {
        return 0;
    }

    @Override
    public void setClearWeatherTime(int time) {

    }

    @Override
    public int getWanderingTraderSpawnDelay() {
        return 0;
    }

    @Override
    public void setWanderingTraderSpawnDelay(int delay) {

    }

    @Override
    public int getWanderingTraderSpawnChance() {
        return 0;
    }

    @Override
    public void setWanderingTraderSpawnChance(int chance) {

    }

    @Nullable
    @Override
    public UUID getWanderingTraderId() {
        return UUID.randomUUID();
    }

    @Override
    public void setWanderingTraderId(UUID id) {

    }

    @Override
    public GameType getGameType() {
        return GameType.SPECTATOR;
    }

    @Nullable
    @Override
    public WorldBorder.Settings getWorldBorder() {
        // Consumed only by the createLevels save/load path, which preview never runs.
        return null;
    }

    @Override
    public void setWorldBorder(WorldBorder.Settings settings) {

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
    public void setGameType(GameType type) {

    }

    @Override
    public TimerQueue<MinecraftServer> getScheduledEvents() {
        return null;
    }

    @Override
    public void setGameTime(long time) {

    }

    @Override
    public void setDayTime(long time) {

    }

    @Override
    public GameRules getGameRules() {
        return new GameRules();
    }

    @Override
    public Difficulty getDifficulty() {
        return Difficulty.HARD;
    }

    @Override
    public boolean isDifficultyLocked() {
        return false;
    }

    // --- LevelData methods ---

    @Override
    public BlockPos getSpawnPos() {
        return BlockPos.ZERO;
    }

    @Override
    public float getSpawnAngle() {
        return 0.0F;
    }

    @Override
    public long getGameTime() {
        return 0;
    }

    @Override
    public long getDayTime() {
        return 0;
    }

    @Override
    public boolean isThundering() {
        return false;
    }

    @Override
    public boolean isRaining() {
        return false;
    }

    @Override
    public void setRaining(boolean raining) {

    }

    @Override
    public boolean isHardcore() {
        return false;
    }

    // --- WritableLevelData methods ---

    @Override
    public void setSpawn(BlockPos pos, float angle) {

    }
}
