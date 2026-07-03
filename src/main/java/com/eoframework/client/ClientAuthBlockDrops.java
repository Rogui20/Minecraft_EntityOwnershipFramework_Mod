package com.eoframework.client;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class ClientAuthBlockDrops {
    public static List<ItemStack> getPredictedDrops(BlockState state, ItemStack tool) {
        return ClientBlockLootProfiles.getDrops(state, tool);
    }
}