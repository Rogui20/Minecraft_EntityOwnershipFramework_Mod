package com.eoframework.common;

import com.eoframework.EOFramework;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = EOFramework.MODID)
public class EOFPlayerEvents {

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            OwnershipManager.removePlayer(player.getUUID());
            ChunkOwnershipManager.removePlayer(player.getUUID());
        }
    }
}