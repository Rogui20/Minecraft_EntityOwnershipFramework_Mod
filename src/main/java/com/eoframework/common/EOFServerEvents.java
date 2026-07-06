package com.eoframework.common;

import com.eoframework.EOFramework;
import com.eoframework.network.ReservedEntityIdsS2CPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = EOFramework.MODID, bus = EventBusSubscriber.Bus.GAME)
public class EOFServerEvents {
    private static int NEXT_RESERVED_ENTITY_ID = 1_500_000_000;

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        sendReservedEntityIds(player);
        ServerBlockLootProfileScanner.sendBlockLootProfiles(player);
    }

    public static void sendReservedEntityIds(ServerPlayer player) {
        int start = NEXT_RESERVED_ENTITY_ID;
        int count = 512;
        NEXT_RESERVED_ENTITY_ID += count;

        PacketDistributor.sendToPlayer(
                player,
                new ReservedEntityIdsS2CPayload(start, count)
        );

        EOFramework.LOGGER.info(
                "[EOF ClientAuthEntity] sent reserved ids start={} count={} player={}",
                start,
                count,
                player.getGameProfile().getName()
        );
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ChunkOwnershipManager.tick(event.getServer());
        for (ServerLevel level : event.getServer().getAllLevels()) {
            StorageOwnershipManager.tick(level);
        }
    }
}