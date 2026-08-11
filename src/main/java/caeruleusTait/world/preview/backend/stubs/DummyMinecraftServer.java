// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.stubs;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.DataFixer;
import net.minecraft.SystemReport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.server.players.NameAndId;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.Proxy;
import java.util.Optional;
import java.util.UUID;

public class DummyMinecraftServer extends MinecraftServer {
    public DummyMinecraftServer(
            Thread thread,
            LevelStorageSource.LevelStorageAccess levelStorageAccess,
            PackRepository packRepository,
            WorldStem worldStem,
            Proxy proxy,
            DataFixer dataFixer,
            Services services,
            LevelLoadListener levelLoadListener
    ) {
        // MC 26.1 constructor: 10 params (no NotificationManager)
        super(thread, levelStorageAccess, packRepository, worldStem, Optional.empty(), proxy, dataFixer, services, levelLoadListener, false);
        this.setSingleplayerProfile(new GameProfile(UUID.randomUUID(), "world-preview"));
        this.setDemo(false);
        this.setPlayerList(new DummyPlayerList(this, this.registries(), this.playerDataStorage));
    }
    @Override
    protected boolean initServer() throws IOException {
        return false;
    }

    @Override
    public LevelBasedPermissionSet operatorUserPermissions() {
        return LevelBasedPermissionSet.ALL;
    }

    @Override
    public PermissionSet getFunctionCompilationPermissions() {
        return PermissionSet.NO_PERMISSIONS;
    }

    @Override
    public boolean shouldRconBroadcast() {
        return false;
    }

    @Override
    protected SampleLogger getTickTimeLogger() {
        return new SampleLogger() {
            @Override
            public void logFullSample(long[] ls) {
            }

            @Override
            public void logSample(long l) {
            }

            @Override
            public void logPartialSample(long l, int i) {
            }
        };
    }

    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }

    @Override
    public @NotNull SystemReport fillServerSystemReport(@NotNull SystemReport report) {
        return report;
    }

    @Override
    public boolean isDedicatedServer() {
        return false;
    }

    @Override
    public int getRateLimitPacketsPerSecond() {
        return 0;
    }

    @Override
    public boolean isPublished() {
        return false;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }

    @Override
    public boolean useNativeTransport() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(@NotNull NameAndId profile) {
        return false;
    }

    @Override
    public int getMaxPlayers() {
        return 1;
    }
}
