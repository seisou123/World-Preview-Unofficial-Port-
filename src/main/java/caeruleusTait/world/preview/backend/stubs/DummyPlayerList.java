// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.stubs;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.notifications.NotificationService;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.PlayerDataStorage;

public class DummyPlayerList extends PlayerList {
    public DummyPlayerList(
            MinecraftServer minecraftServer,
            LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess,
            PlayerDataStorage playerDataStorage
    ) {
        super(minecraftServer, layeredRegistryAccess, playerDataStorage, new NotificationService() {
            @Override
            public void playerJoined(net.minecraft.server.level.ServerPlayer player) {}
            @Override
            public void playerLeft(net.minecraft.server.level.ServerPlayer player) {}
            @Override
            public void serverStarted() {}
            @Override
            public void serverShuttingDown() {}
            @Override
            public void serverSaveStarted() {}
            @Override
            public void serverSaveCompleted() {}
            @Override
            public void serverActivityOccured() {}
            @Override
            public void playerOped(net.minecraft.server.players.ServerOpListEntry entry) {}
            @Override
            public void playerDeoped(net.minecraft.server.players.ServerOpListEntry entry) {}
            @Override
            public void playerAddedToAllowlist(net.minecraft.server.players.NameAndId nameAndId) {}
            @Override
            public void playerRemovedFromAllowlist(net.minecraft.server.players.NameAndId nameAndId) {}
            @Override
            public void ipBanned(net.minecraft.server.players.IpBanListEntry entry) {}
            @Override
            public void ipUnbanned(String ip) {}
            @Override
            public void playerBanned(net.minecraft.server.players.UserBanListEntry entry) {}
            @Override
            public void playerUnbanned(net.minecraft.server.players.NameAndId nameAndId) {}
            @Override
            public <T> void onGameRuleChanged(net.minecraft.world.level.gamerules.GameRule<T> rule, T value) {}
            @Override
            public void statusHeartbeat() {}
        });
    }
}
