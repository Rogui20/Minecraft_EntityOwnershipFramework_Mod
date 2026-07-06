package com.eoframework.client;

import com.eoframework.EOFramework;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

public class ClientLocalStorageSession {
    private static boolean active = false;
    private static long suppressUntil = 0;
    private static final Set<BlockPos> positions = new HashSet<>();

    public static void begin(Set<BlockPos> storagePositions) {
        Minecraft mc = Minecraft.getInstance();
        active = true;
        positions.clear();
        for (BlockPos pos : storagePositions) {
            positions.add(pos.immutable());
        }

        suppressUntil = mc.level == null ? 0 : mc.level.getGameTime() + 20;
    }

    public static void endWithGrace(int ticks) {
        Minecraft mc = Minecraft.getInstance();
        active = false;
        suppressUntil = mc.level == null ? 0 : mc.level.getGameTime() + ticks;
    }

    public static boolean shouldSuppressBlockEvent(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        long now = mc.level.getGameTime();

        boolean suppress = (active || now <= suppressUntil) && positions.contains(pos);
        if (suppress) {
            EOFramework.LOGGER.info("[EOF StorageSession] suppress block event pos={}", pos);
        }
        return suppress;
    }

    public static boolean shouldSuppressMenuPacket() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        long now = mc.level.getGameTime();
        boolean suppress = active || now <= suppressUntil;
        if (suppress) {
            EOFramework.LOGGER.info("[EOF StorageSession] suppress open screen pos={}", positions);
        }
        return suppress;
    }
}