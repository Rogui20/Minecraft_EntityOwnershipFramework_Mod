package com.eoframework.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BlockOwnershipManager {
    private static final int CELL_SIZE = 16;
    private static final Map<CellKey, UUID> OWNERS = new HashMap<>();

    public static UUID getOrAssignOwner(ServerLevel level, BlockPos pos, ServerPlayer fallbackOwner) {
        CellKey key = CellKey.from(level, pos);
        return OWNERS.computeIfAbsent(key, k -> fallbackOwner.getUUID());
    }

    public static boolean isOwner(ServerLevel level, BlockPos pos, ServerPlayer player) {
        UUID owner = getOrAssignOwner(level, pos, player);
        return owner.equals(player.getUUID());
    }

    public static UUID getOwner(ServerLevel level, BlockPos pos) {
        return OWNERS.get(CellKey.from(level, pos));
    }

    public static void setOwner(ServerLevel level, BlockPos pos, UUID owner) {
        OWNERS.put(CellKey.from(level, pos), owner);
    }

    private record CellKey(String dimension, int x, int y, int z) {
        static CellKey from(ServerLevel level, BlockPos pos) {
            return new CellKey(
                    level.dimension().location().toString(),
                    Math.floorDiv(pos.getX(), CELL_SIZE),
                    Math.floorDiv(pos.getY(), CELL_SIZE),
                    Math.floorDiv(pos.getZ(), CELL_SIZE)
            );
        }
    }
}