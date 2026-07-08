package com.eoframework.common;

import com.eoframework.common.EOFDebug;
import com.eoframework.EOFramework;
import com.eoframework.network.ReservedEntityIdsS2CPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = EOFramework.MODID, bus = EventBusSubscriber.Bus.GAME)
public class EOFServerEvents {
    private static int NEXT_RESERVED_ENTITY_ID = 1_500_000_000;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!EOFDebug.isDisabled("LootProfileSync")) {
            ServerBlockLootProfileScanner.requestAsyncRebuild();
        }
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        if (!EOFDebug.isDisabled("LootProfileSync")) {
            ServerBlockLootProfileScanner.requestAsyncRebuild();
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        EOFPerf.time("PlayerLoggedInEvent", () -> {
            sendReservedEntityIds(player);
            if (!EOFDebug.isDisabled("LootProfileSync")) {
                ServerBlockLootProfileScanner.sendBlockLootProfiles(player);
            } else {
                EOFramework.LOGGER.warn("[EOF Debug] eof.debug.disableLootProfileSync=true; skipped initial loot profile sync for {}", player.getGameProfile().getName());
            }
        });
    }

    public static void sendReservedEntityIds(ServerPlayer player) {
        int start = NEXT_RESERVED_ENTITY_ID;
        int count = 512;
        NEXT_RESERVED_ENTITY_ID += count;

        EOFPerf.time("PacketDistributor.sendReservedEntityIds", () -> PacketDistributor.sendToPlayer(
                player,
                new ReservedEntityIdsS2CPayload(start, count)
        ));

        EOFDebug.log(EOFDebug.Flag.BLOCK_OWNERSHIP, 
                "[EOF ClientAuthEntity] sent reserved ids start={} count={} player={}",
                start,
                count,
                player.getGameProfile().getName()
        );
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof ItemEntity item) {
            ItemOwnershipManager.unregister(item.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!EOFDebug.isDisabled("ChunkOwnershipSync")) {
            EOFPerf.time("ChunkOwnershipManager.tick", () -> ChunkOwnershipManager.tick(event.getServer()));
        }
        ServerLevel overworld = event.getServer().overworld();
        if (overworld != null && !EOFDebug.isDisabled("LootProfileSync")) {
            EOFPerf.time("ServerBlockLootProfileScanner.tick", () -> ServerBlockLootProfileScanner.tick(overworld));
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (!EOFDebug.isDisabled("StorageSnapshots")) {
                EOFPerf.time("StorageOwnershipManager.tick " + level.dimension().location(), () -> StorageOwnershipManager.tick(level));
            }
            if (!EOFDebug.isDisabled("ItemOwnership")) {
                EOFPerf.time("ItemOwnershipManager.tick " + level.dimension().location(), () -> ItemOwnershipManager.tick(level));
            }
        }
    }
}
