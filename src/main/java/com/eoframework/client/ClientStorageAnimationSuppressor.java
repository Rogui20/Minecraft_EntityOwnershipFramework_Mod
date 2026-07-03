package com.eoframework.client;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class ClientStorageAnimationSuppressor {
    private static final Map<BlockPos, Long> SUPPRESSED_UNTIL = new HashMap<>();

    public static void suppress(BlockPos pos, long gameTime, int ticks) {
        SUPPRESSED_UNTIL.put(pos.immutable(), gameTime + ticks);
    }

    public static boolean shouldSuppress(BlockPos pos, long gameTime) {
        Long until = SUPPRESSED_UNTIL.get(pos);
        if (until == null) return false;

        if (gameTime > until) {
            SUPPRESSED_UNTIL.remove(pos);
            return false;
        }

        return true;
    }
}