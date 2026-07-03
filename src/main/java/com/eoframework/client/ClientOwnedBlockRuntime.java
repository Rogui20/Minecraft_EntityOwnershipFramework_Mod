package com.eoframework.client;

import com.eoframework.network.BlockBreakRequestC2SPayload;
import com.eoframework.network.BlockPlaceRequestC2SPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClientOwnedBlockRuntime {
    private static final int CELL_SIZE = 16;
    private static final Map<CellKey, UUID> CELL_OWNERS = new HashMap<>();
    private static final Map<BlockPos, Long> BREAK_ECHO_SUPPRESS = new HashMap<>();

    public static void suppressBreakEcho(BlockPos pos, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BREAK_ECHO_SUPPRESS.put(pos.immutable(), mc.level.getGameTime() + ticks);
    }

    public static boolean shouldSuppressBreakEcho(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        Long until = BREAK_ECHO_SUPPRESS.get(pos);
        if (until == null) return false;

        if (mc.level.getGameTime() > until) {
            BREAK_ECHO_SUPPRESS.remove(pos);
            return false;
        }

        return true;
    }

    public static boolean isCellOwnedByMe(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;

        UUID owner = CELL_OWNERS.get(CellKey.from(pos));
        return owner != null && owner.equals(mc.player.getUUID());
    }

    public static void setCellOwner(int cellX, int cellY, int cellZ, UUID owner) {
        CELL_OWNERS.put(new CellKey(cellX, cellY, cellZ), owner);
    }

    public static void requestPlace(BlockPos pos, Direction face, ItemStack stack) {
        PacketDistributor.sendToServer(new BlockPlaceRequestC2SPayload(pos, face, stack.copyWithCount(1)));
    }

    public static void requestBreakAsNonOwner(BlockPos pos) {
        PacketDistributor.sendToServer(new BlockBreakRequestC2SPayload(pos, false));
    }

    public static void notifyOwnerBreakWithClientDrops(BlockPos pos) {
        PacketDistributor.sendToServer(new BlockBreakRequestC2SPayload(pos, true));
    }

    public static void spawnOwnerDrops(BlockPos pos, BlockState state, ItemStack tool) {
        for (ItemStack drop : ClientAuthBlockDrops.getPredictedDrops(state, tool)) {
            if (!drop.isEmpty()) {
                ClientAuthEntities.spawnAndSendItem(drop.copy(), pos);
            }
        }

        if (ClientStorageCache.has(pos)) {
            List<ItemStack> storageItems = ClientStorageCache.get(pos);

            for (ItemStack stack : storageItems) {
                if (!stack.isEmpty()) {
                    ClientAuthEntities.spawnAndSendItem(stack.copy(), pos);
                }
            }
        }
    }

    private record CellKey(int x, int y, int z) {
        static CellKey from(BlockPos pos) {
            return new CellKey(
                    Math.floorDiv(pos.getX(), CELL_SIZE),
                    Math.floorDiv(pos.getY(), CELL_SIZE),
                    Math.floorDiv(pos.getZ(), CELL_SIZE)
            );
        }
    }

    public static void handleRemoteBreakRequest(UUID requester, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        if (!isCellOwnedByMe(pos)) {
            return;
        }

        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }

        ItemStack tool = mc.player.getMainHandItem().copy();

        // Remove visualmente no owner.
        mc.level.destroyBlock(pos, false);

        // Evita eco atrasado de partícula/som do servidor.
        suppressBreakEcho(pos, 100);

        // Owner spawna drops client-auth.
        spawnOwnerDrops(pos, state, tool);

        // Owner confirma ao servidor que quebrou e já spawnou os drops.
        notifyOwnerBreakWithClientDrops(pos);
    }
}