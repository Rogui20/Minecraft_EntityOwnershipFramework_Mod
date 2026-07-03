package com.eoframework.client;

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

        if (active && positions.contains(pos)) {
            return true;
        }

        return now <= suppressUntil && positions.contains(pos);
    }

    public static boolean shouldSuppressMenuPacket() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        long now = mc.level.getGameTime();
        return active || now <= suppressUntil;
    }
}