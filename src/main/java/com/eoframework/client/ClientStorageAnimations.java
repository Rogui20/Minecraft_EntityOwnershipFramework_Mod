package com.eoframework.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.HashSet;
import java.util.Set;

public class ClientStorageAnimations {
    private static final Set<BlockPos> LOCALLY_OPENED = new HashSet<>();

    public static void open(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (BlockPos p : storageAnimationPositions(pos)) {
            BlockState state = mc.level.getBlockState(p);

            if (state.getBlock() instanceof ChestBlock || state.getBlock() instanceof ShulkerBoxBlock) {
                mc.level.blockEvent(p, state.getBlock(), 1, 1);
                ClientStorageAnimationSuppressor.suppress(p, mc.level.getGameTime(), 20);
                LOCALLY_OPENED.add(p.immutable());
            }

            if (state.getBlock() instanceof BarrelBlock) {
                // Barril usa BlockState OPEN, então deixa para depois se precisar.
                LOCALLY_OPENED.add(p.immutable());
            }
        }
    }

    public static void closeAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LOCALLY_OPENED.clear();
            return;
        }

        for (BlockPos p : LOCALLY_OPENED) {
            BlockState state = mc.level.getBlockState(p);

            if (state.getBlock() instanceof ChestBlock || state.getBlock() instanceof ShulkerBoxBlock) {
                mc.level.blockEvent(p, state.getBlock(), 1, 0);
            }
        }

        LOCALLY_OPENED.clear();
    }

    private static Set<BlockPos> storageAnimationPositions(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Set<BlockPos> result = new HashSet<>();
        result.add(pos.immutable());

        if (mc.level == null) return result;

        BlockState state = mc.level.getBlockState(pos);

        if (!(state.getBlock() instanceof ChestBlock)) {
            return result;
        }

        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return result;
        }

        BlockPos other = findConnectedChestPos(pos, state);
        if (other != null) {
            result.add(other.immutable());
        }

        return result;
    }

    private static BlockPos findConnectedChestPos(BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;

        Direction facing = state.getValue(ChestBlock.FACING);

        for (Direction dir : new Direction[]{facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockPos other = pos.relative(dir);
            BlockState otherState = mc.level.getBlockState(other);

            if (otherState.getBlock() != state.getBlock()) continue;
            if (!otherState.hasProperty(ChestBlock.TYPE)) continue;
            if (!otherState.hasProperty(ChestBlock.FACING)) continue;
            if (otherState.getValue(ChestBlock.TYPE) == ChestType.SINGLE) continue;
            if (otherState.getValue(ChestBlock.FACING) != facing) continue;

            return other.immutable();
        }

        return null;
    }

    public static Set<BlockPos> animationPositions(BlockPos pos) {
        return storageAnimationPositions(pos);
    }
}