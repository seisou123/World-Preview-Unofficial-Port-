// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.stubs;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.PlayerDataStorage;

public class DummyPlayerList extends PlayerList {
    public DummyPlayerList(
            MinecraftServer minecraftServer,
            LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess,
            PlayerDataStorage playerDataStorage
    ) {
        super(minecraftServer, layeredRegistryAccess, playerDataStorage, 1);
    }
}
