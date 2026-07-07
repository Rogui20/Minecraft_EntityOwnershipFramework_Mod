package com.eoframework.common;

import com.eoframework.common.EOFDebug;
import com.eoframework.EOFramework;
import com.eoframework.network.ChunkOwnerSyncS2CPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ChunkOwnershipManager {
    private static final int SYNC_RADIUS_CHUNKS = 2;
    private static final Map<ChunkKey, ChunkOwnershipState> OWNERSHIPS = new HashMap<>();
    private static final Map<UUID, ChunkKey> PLAYER_CHUNKS = new HashMap<>();

    public static void tick(MinecraftServer server) {
        Set<UUID> seenPlayers = new HashSet<>();
        Set<ChunkKey> changed = new HashSet<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            seenPlayers.add(player.getUUID());
            ChunkKey current = ChunkKey.from(player.serverLevel(), player.chunkPosition());
            ChunkKey previous = PLAYER_CHUNKS.put(player.getUUID(), current);

            if (previous != null && !previous.equals(current)) {
                removeFromChunk(previous, player.getUUID(), changed);
            }

            ChunkOwnershipState state = OWNERSHIPS.computeIfAbsent(current, key -> new ChunkOwnershipState());
            if (state.present.add(player.getUUID())) {
                changed.add(current);
            }
            if (promoteOwner(state)) {
                changed.add(current);
            }
        }

        List<UUID> stalePlayers = new ArrayList<>();
        for (UUID uuid : PLAYER_CHUNKS.keySet()) {
            if (!seenPlayers.contains(uuid)) stalePlayers.add(uuid);
        }
        for (UUID uuid : stalePlayers) {
            ChunkKey previous = PLAYER_CHUNKS.remove(uuid);
            if (previous != null) removeFromChunk(previous, uuid, changed);
        }

        for (ChunkKey key : changed) {
            syncChunk(server, key);
        }
    }

    public static UUID getOwner(ServerLevel level, BlockPos pos) {
        ChunkOwnershipState state = OWNERSHIPS.get(ChunkKey.from(level, new ChunkPos(pos)));
        return state == null ? null : state.owner;
    }

    public static UUID getOrAssignOwner(ServerLevel level, BlockPos pos, ServerPlayer fallbackOwner) {
        ChunkKey key = ChunkKey.from(level, new ChunkPos(pos));
        ChunkOwnershipState state = OWNERSHIPS.computeIfAbsent(key, ignored -> new ChunkOwnershipState());
        if (state.present.add(fallbackOwner.getUUID())) {
            PLAYER_CHUNKS.put(fallbackOwner.getUUID(), key);
        }
        promoteOwner(state);
        return state.owner;
    }

    public static boolean isOwner(ServerLevel level, BlockPos pos, ServerPlayer player) {
        UUID owner = getOrAssignOwner(level, pos, player);
        return player.getUUID().equals(owner);
    }

    public static void removePlayer(UUID playerUuid) {
        ChunkKey previous = PLAYER_CHUNKS.remove(playerUuid);
        if (previous != null) {
            Set<ChunkKey> changed = new HashSet<>();
            removeFromChunk(previous, playerUuid, changed);
        }
    }

    private static void removeFromChunk(ChunkKey key, UUID playerUuid, Set<ChunkKey> changed) {
        ChunkOwnershipState state = OWNERSHIPS.get(key);
        if (state == null) return;

        if (state.present.remove(playerUuid)) changed.add(key);
        if (playerUuid.equals(state.owner)) {
            state.owner = null;
            changed.add(key);
        }

        if (state.present.isEmpty()) {
            OWNERSHIPS.remove(key);
            changed.add(key);
        } else if (promoteOwner(state)) {
            changed.add(key);
        }
    }

    private static boolean promoteOwner(ChunkOwnershipState state) {
        UUID previous = state.owner;
        if (state.owner == null || !state.present.contains(state.owner)) {
            state.owner = state.present.stream().findFirst().orElse(null);
        }
        return previous == null ? state.owner != null : !previous.equals(state.owner);
    }

    private static void syncChunk(MinecraftServer server, ChunkKey key) {
        ServerLevel level = server.getLevel(key.dimension);
        if (level == null) return;

        ChunkOwnershipState state = OWNERSHIPS.get(key);
        UUID owner = state == null ? null : state.owner;
        ChunkOwnerSyncS2CPayload payload = new ChunkOwnerSyncS2CPayload(key.dimension.location().toString(), key.x, key.z, owner);

        for (ServerPlayer player : level.players()) {
            ChunkPos playerChunk = player.chunkPosition();
            if (Math.abs(playerChunk.x - key.x) <= SYNC_RADIUS_CHUNKS && Math.abs(playerChunk.z - key.z) <= SYNC_RADIUS_CHUNKS) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }

        EOFDebug.log(EOFDebug.Flag.BLOCK_OWNERSHIP, "[EOF ChunkOwnership] sync dimension={} chunk=({}, {}) owner={} occupied={}", key.dimension.location(), key.x, key.z, owner, state != null ? state.present.size() : 0);
    }

    private static class ChunkOwnershipState {
        private UUID owner;
        private final LinkedHashSet<UUID> present = new LinkedHashSet<>();
    }

    private record ChunkKey(ResourceKey<Level> dimension, int x, int z) {
        static ChunkKey from(ServerLevel level, ChunkPos pos) {
            return new ChunkKey(level.dimension(), pos.x, pos.z);
        }
    }
}
