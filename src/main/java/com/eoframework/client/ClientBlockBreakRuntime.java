package com.eoframework.client;

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
    private static BlockPos nonOwnerAssistPos;

    public static boolean beginNonOwnerAssist(BlockPos pos) {
        if (ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) return false;
        if (!pos.equals(nonOwnerAssistPos)) {
            endNonOwnerAssist();
            nonOwnerAssistPos = pos.immutable();
            PacketDistributor.sendToServer(new BlockBreakAssistC2SPayload(nonOwnerAssistPos, true));
        }
        return true;
    }

    public static boolean continueNonOwnerAssist(BlockPos pos) {
        if (ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) return false;
        beginNonOwnerAssist(pos);
        return true;
    }

    public static void endNonOwnerAssist() {
        if (nonOwnerAssistPos == null) return;
        PacketDistributor.sendToServer(new BlockBreakAssistC2SPayload(nonOwnerAssistPos, false));
        nonOwnerAssistPos = null;
    }

    public static void handleAssist(UUID requester, BlockPos pos, boolean active) {
        if (!ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) return;
        if (active) {
            ASSISTS.put(requester, new Assist(pos.immutable(), Minecraft.getInstance().level.getGameTime() + 10));
        } else {
            ASSISTS.remove(requester);
        }
    }

    public static int activeAssistCount(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        long now = mc.level.getGameTime();
        int count = 0;
        Iterator<Map.Entry<UUID, Assist>> iterator = ASSISTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Assist assist = iterator.next().getValue();
            if (assist.untilTick < now) {
                iterator.remove();
            } else if (assist.pos.equals(pos)) {
                count++;
            }
        }
        return count;
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
        mc.level.destroyBlockProgress(breakerId, pos, stage);
    }

    private record Assist(BlockPos pos, long untilTick) {}
}
