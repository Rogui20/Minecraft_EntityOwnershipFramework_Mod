package com.eoframework.common;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.*;

public class ClientAuthDropSuppressor {
    private static final Set<Key> SUPPRESSED = new HashSet<>();

    public static void mark(ServerPlayer player, BlockPos pos) {
        SUPPRESSED.add(new Key(player.getUUID(), player.level().dimension(), pos.immutable()));
    }

    public static boolean consume(ServerPlayer player, BlockPos pos) {
        return SUPPRESSED.remove(new Key(player.getUUID(), player.level().dimension(), pos.immutable()));
    }

    private record Key(UUID player, ResourceKey<Level> dimension, BlockPos pos) {}
}