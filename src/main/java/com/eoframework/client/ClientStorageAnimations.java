package com.eoframework.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
                playChestSound(p, state, SoundEvents.CHEST_OPEN);
                ClientStorageAnimationSuppressor.suppress(p, mc.level.getGameTime(), 20);
                LOCALLY_OPENED.add(p.immutable());
            }

            if (state.getBlock() instanceof BarrelBlock) {
                setBarrelOpen(p, state, true);
                playBarrelSound(p, state, SoundEvents.BARREL_OPEN);
                ClientStorageAnimationSuppressor.suppress(p, mc.level.getGameTime(), 20);
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
                playChestSound(p, state, SoundEvents.CHEST_CLOSE);
                ClientStorageAnimationSuppressor.suppress(p, mc.level.getGameTime(), 20);
            }

            if (state.getBlock() instanceof BarrelBlock) {
                setBarrelOpen(p, state, false);
                playBarrelSound(p, state, SoundEvents.BARREL_CLOSE);
                ClientStorageAnimationSuppressor.suppress(p, mc.level.getGameTime(), 20);
            }
        }

        LOCALLY_OPENED.clear();
    }

    private static void playChestSound(BlockPos pos, BlockState state, SoundEvent sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(state.getBlock() instanceof ChestBlock)) return;

        ChestType chestType = state.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.LEFT) return;

        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        if (chestType == ChestType.RIGHT) {
            Direction direction = ChestBlock.getConnectedDirection(state);
            x += direction.getStepX() * 0.5D;
            z += direction.getStepZ() * 0.5D;
        }

        mc.level.playLocalSound(x, y, z, sound, SoundSource.BLOCKS, 0.5F, mc.level.random.nextFloat() * 0.1F + 0.9F, false);
    }

    private static void setBarrelOpen(BlockPos pos, BlockState state, boolean open) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !state.hasProperty(BlockStateProperties.OPEN)) return;
        mc.level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 3);
    }

    private static void playBarrelSound(BlockPos pos, BlockState state, SoundEvent sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !state.hasProperty(BarrelBlock.FACING)) return;

        var normal = state.getValue(BarrelBlock.FACING).getNormal();
        double x = pos.getX() + 0.5D + normal.getX() / 2.0D;
        double y = pos.getY() + 0.5D + normal.getY() / 2.0D;
        double z = pos.getZ() + 0.5D + normal.getZ() / 2.0D;
        mc.level.playLocalSound(x, y, z, sound, SoundSource.BLOCKS, 0.5F, mc.level.random.nextFloat() * 0.1F + 0.9F, false);
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