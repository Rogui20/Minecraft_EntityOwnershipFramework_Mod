package com.eoframework.client;

import com.eoframework.EOFramework;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

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

    public static boolean shouldSuppressStorageSound(double x, double y, double z, SoundEvent sound, SoundSource source) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || source != SoundSource.BLOCKS || !isStorageSound(sound)) return false;

        BlockPos soundPos = BlockPos.containing(x, y, z);
        long now = mc.level.getGameTime();
        boolean suppress = (active || now <= suppressUntil) && isNearSuppressedStorage(soundPos);
        if (suppress) {
            EOFramework.LOGGER.info("[EOF StorageSession] suppress storage sound pos={} sound={}", soundPos, sound);
        }
        return suppress;
    }

    private static boolean isStorageSound(SoundEvent sound) {
        return sound == SoundEvents.CHEST_OPEN
                || sound == SoundEvents.CHEST_CLOSE
                || sound == SoundEvents.BARREL_OPEN
                || sound == SoundEvents.BARREL_CLOSE
                || sound == SoundEvents.SHULKER_BOX_OPEN
                || sound == SoundEvents.SHULKER_BOX_CLOSE;
    }

    private static boolean isNearSuppressedStorage(BlockPos pos) {
        for (BlockPos storagePos : positions) {
            if (Math.abs(storagePos.getX() - pos.getX()) <= 1
                    && Math.abs(storagePos.getY() - pos.getY()) <= 1
                    && Math.abs(storagePos.getZ() - pos.getZ()) <= 1) {
                return true;
            }
        }
        return false;
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