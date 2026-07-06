package com.eoframework.client;

import com.eoframework.EOFramework;
import com.eoframework.network.BlockBreakAssistC2SPayload;
import com.eoframework.network.BlockBreakProgressC2SPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class ClientBlockBreakRuntime {
    private static final Map<UUID, Assist> ASSISTS = new HashMap<>();
    private static final Map<BlockPos, Float> OWNER_ASSIST_PROGRESS = new HashMap<>();
    private static BlockPos nonOwnerAssistPos;

    public static boolean beginNonOwnerAssist(BlockPos pos) {
        if (ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) return false;
        if (!pos.equals(nonOwnerAssistPos)) {
            endNonOwnerAssist();
            nonOwnerAssistPos = pos.immutable();
            sendNonOwnerAssist(nonOwnerAssistPos, true, "start");
        }
        return true;
    }

    public static boolean continueNonOwnerAssist(BlockPos pos) {
        if (ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) return false;
        if (!pos.equals(nonOwnerAssistPos)) {
            beginNonOwnerAssist(pos);
        } else {
            sendNonOwnerAssist(nonOwnerAssistPos, true, "update");
        }
        return true;
    }

    public static void endNonOwnerAssist() {
        if (nonOwnerAssistPos == null) return;
        sendNonOwnerAssist(nonOwnerAssistPos, false, "stop");
        nonOwnerAssistPos = null;
    }

    private static void sendNonOwnerAssist(BlockPos pos, boolean active, String phase) {
        float speed = active ? localDestroySpeed(pos) : 0.0F;
        PacketDistributor.sendToServer(new BlockBreakAssistC2SPayload(pos, active, speed));
        EOFramework.LOGGER.info("[EOF BlockBreakAssist] assist {} sent pos={} speed={}", phase, pos, speed);
    }

    private static float localDestroySpeed(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0.0F;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return 0.0F;
        return state.getDestroyProgress(mc.player, mc.level, pos);
    }

    public static void handleAssist(UUID requester, BlockPos pos, boolean active, float speed) {
        if (!ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) return;
        if (active) {
            Minecraft mc = Minecraft.getInstance();
            long untilTick = mc.level != null ? mc.level.getGameTime() + 10 : 0;
            ASSISTS.put(requester, new Assist(pos.immutable(), untilTick, speed));
        } else {
            ASSISTS.remove(requester);
        }
        EOFramework.LOGGER.info("[EOF BlockBreakAssist] assist received pos={} speed={} active={}", pos, speed, active);
    }

    public static void tickOwnerAssists() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.gameMode == null) return;
        Map<BlockPos, Float> speeds = activeAssistSpeeds();
        OWNER_ASSIST_PROGRESS.keySet().removeIf(pos -> !speeds.containsKey(pos));
        for (Map.Entry<BlockPos, Float> entry : speeds.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) continue;
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir()) {
                sendOwnerProgress(pos, -1);
                OWNER_ASSIST_PROGRESS.remove(pos);
                continue;
            }
            float value = OWNER_ASSIST_PROGRESS.getOrDefault(pos, 0.0F) + entry.getValue();
            int stage = value <= 0.0F ? -1 : Math.min(9, (int)(value * 10.0F));
            EOFramework.LOGGER.info("[EOF BlockBreakAssist] progress pos={} value={} stage={}", pos, value, stage);
            sendOwnerProgress(pos, stage);
            if (value >= 1.0F) {
                mc.gameMode.destroyBlock(pos);
                sendOwnerProgress(pos, -1);
                OWNER_ASSIST_PROGRESS.remove(pos);
            } else {
                OWNER_ASSIST_PROGRESS.put(pos.immutable(), value);
            }
        }
    }

    private static Map<BlockPos, Float> activeAssistSpeeds() {
        Minecraft mc = Minecraft.getInstance();
        Map<BlockPos, Float> speeds = new HashMap<>();
        if (mc.level == null) return speeds;
        long now = mc.level.getGameTime();
        Iterator<Map.Entry<UUID, Assist>> iterator = ASSISTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Assist assist = iterator.next().getValue();
            if (assist.untilTick < now) {
                iterator.remove();
            } else {
                speeds.merge(assist.pos, assist.speed, Float::sum);
            }
        }
        return speeds;
    }

    public static int activeAssistCount(BlockPos pos) {
        return (int) ASSISTS.values().stream().filter(assist -> assist.pos.equals(pos)).count();
    }

    public static float assistedProgress(BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0.0F;
        return state.getDestroyProgress(mc.player, mc.level, pos) * activeAssistCount(pos);
    }

    public static void sendOwnerProgress(BlockPos pos, int stage) {
        PacketDistributor.sendToServer(new BlockBreakProgressC2SPayload(pos, stage));
    }

    public static void showRemoteProgress(int breakerId, BlockPos pos, int stage) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        EOFramework.LOGGER.info("[EOF BlockBreakAssist] progress received pos={} stage={}", pos, stage);
        mc.level.destroyBlockProgress(breakerId, pos, stage);
    }

    private record Assist(BlockPos pos, long untilTick, float speed) {}
}
