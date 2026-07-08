package com.eoframework.common;

import com.eoframework.network.ChunkOwnerSyncS2CPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public class ChunkOwnershipManager {
    private static final int SYNC_RADIUS_CHUNKS = 2;
    private static final Map<DimensionChunkKey, ChunkOwnerState> OWNERSHIPS = new HashMap<>();
    private static int cleanupTicker = 0;

    public static void claim(ServerPlayer player, int chunkX, int chunkZ) {
        ServerLevel level = player.serverLevel();
        if (!isPlayerInChunk(player, chunkX, chunkZ)) return;
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) return;

        DimensionChunkKey key = new DimensionChunkKey(level.dimension(), chunkX, chunkZ);
        ChunkOwnerState state = OWNERSHIPS.computeIfAbsent(key, ignored -> new ChunkOwnerState());
        if (state.ownerUuid == null) {
            state.ownerUuid = player.getUUID();
        } else if (!state.ownerUuid.equals(player.getUUID())) {
            state.queue.add(player.getUUID());
        }
        promoteNextValid(level, key, state);
        sync(level, key);
    }

    public static void leaving(ServerPlayer player, int chunkX, int chunkZ) {
        ServerLevel level = player.serverLevel();
        DimensionChunkKey key = new DimensionChunkKey(level.dimension(), chunkX, chunkZ);
        ChunkOwnerState state = OWNERSHIPS.get(key);
        if (state == null) return;
        if (isPlayerInChunk(player, chunkX, chunkZ)) return;

        state.queue.remove(player.getUUID());
        if (player.getUUID().equals(state.ownerUuid)) {
            state.ownerUuid = null;
        }
        promoteNextValid(level, key, state);
        sync(level, key);
    }

    public static void tick(MinecraftServer server) {
        if (++cleanupTicker < 20) return;
        cleanupTicker = 0;
        List<DimensionChunkKey> changed = new ArrayList<>();
        for (var entry : OWNERSHIPS.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey().dimension);
            if (level == null) continue;
            if (promoteNextValid(level, entry.getKey(), entry.getValue())) changed.add(entry.getKey());
        }
        for (DimensionChunkKey key : changed) {
            ServerLevel level = server.getLevel(key.dimension);
            if (level != null) sync(level, key);
        }
    }

    public static UUID getOwner(ServerLevel level, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        ChunkOwnerState state = OWNERSHIPS.get(new DimensionChunkKey(level.dimension(), chunk.x, chunk.z));
        return state == null ? null : state.ownerUuid;
    }

    public static UUID getOrAssignOwner(ServerLevel level, BlockPos pos, ServerPlayer fallbackOwner) {
        ChunkPos chunk = new ChunkPos(pos);
        claim(fallbackOwner, chunk.x, chunk.z);
        UUID owner = getOwner(level, pos);
        return owner != null ? owner : fallbackOwner.getUUID();
    }

    public static boolean isOwner(ServerLevel level, BlockPos pos, ServerPlayer player) {
        UUID owner = getOrAssignOwner(level, pos, player);
        return player.getUUID().equals(owner);
    }

    public static void removePlayer(UUID playerUuid) {
        for (Iterator<Map.Entry<DimensionChunkKey, ChunkOwnerState>> it = OWNERSHIPS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<DimensionChunkKey, ChunkOwnerState> entry = it.next();
            ChunkOwnerState state = entry.getValue();
            state.queue.remove(playerUuid);
            if (playerUuid.equals(state.ownerUuid)) state.ownerUuid = null;
            if (state.ownerUuid == null && state.queue.isEmpty()) it.remove();
        }
    }

    private static boolean promoteNextValid(ServerLevel level, DimensionChunkKey key, ChunkOwnerState state) {
        UUID previous = state.ownerUuid;
        state.queue.removeIf(uuid -> !isValidCandidate(level, key, uuid));
        if (!isValidCandidate(level, key, state.ownerUuid)) {
            state.ownerUuid = null;
            Iterator<UUID> iterator = state.queue.iterator();
            while (iterator.hasNext()) {
                UUID candidate = iterator.next();
                iterator.remove();
                if (isValidCandidate(level, key, candidate)) {
                    state.ownerUuid = candidate;
                    break;
                }
            }
            if (state.ownerUuid == null) {
                for (ServerPlayer player : level.players()) {
                    if (isPlayerInChunk(player, key.x, key.z)) {
                        state.ownerUuid = player.getUUID();
                        break;
                    }
                }
            }
        }
        if (state.ownerUuid == null && state.queue.isEmpty()) OWNERSHIPS.remove(key);
        return !Objects.equals(previous, state.ownerUuid);
    }

    private static boolean isValidCandidate(ServerLevel level, DimensionChunkKey key, UUID uuid) {
        if (uuid == null) return false;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
        return player != null && player.serverLevel() == level && isPlayerInChunk(player, key.x, key.z);
    }

    private static boolean isPlayerInChunk(ServerPlayer player, int chunkX, int chunkZ) {
        ChunkPos pos = player.chunkPosition();
        return pos.x == chunkX && pos.z == chunkZ;
    }

    private static void sync(ServerLevel level, DimensionChunkKey key) {
        ChunkOwnerState state = OWNERSHIPS.get(key);
        UUID owner = state == null ? null : state.ownerUuid;
        ChunkOwnerSyncS2CPayload payload = new ChunkOwnerSyncS2CPayload(key.dimension.location().toString(), key.x, key.z, owner);
        for (ServerPlayer player : level.players()) {
            ChunkPos playerChunk = player.chunkPosition();
            if (Math.abs(playerChunk.x - key.x) <= SYNC_RADIUS_CHUNKS && Math.abs(playerChunk.z - key.z) <= SYNC_RADIUS_CHUNKS) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private static class ChunkOwnerState {
        private UUID ownerUuid;
        private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    }

    private record DimensionChunkKey(ResourceKey<Level> dimension, int x, int z) {}
}
