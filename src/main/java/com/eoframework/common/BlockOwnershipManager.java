package com.eoframework.common;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Compatibility facade for older block/cell ownership call sites.
 * Ownership authority is now chunk-wide and delegated to {@link ChunkOwnershipManager}.
 */
@Deprecated
public class BlockOwnershipManager {
    public static UUID getOrAssignOwner(ServerLevel level, BlockPos pos, ServerPlayer fallbackOwner) {
        return ChunkOwnershipManager.getOrAssignOwner(level, pos, fallbackOwner);
    }

    public static boolean isOwner(ServerLevel level, BlockPos pos, ServerPlayer player) {
        return ChunkOwnershipManager.isOwner(level, pos, player);
    }

    public static UUID getOwner(ServerLevel level, BlockPos pos) {
        return ChunkOwnershipManager.getOwner(level, pos);
    }

    public static void setOwner(ServerLevel level, BlockPos pos, UUID owner) {
        // Chunk ownership is derived from players present in the chunk; direct mutation is intentionally unsupported.
    }
}
