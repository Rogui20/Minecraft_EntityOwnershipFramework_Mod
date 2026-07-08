package com.eoframework.common;

import com.eoframework.common.EOFDebug;
import com.eoframework.EOFramework;
import com.eoframework.network.BlockLootProfileS2CPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public class ServerBlockLootProfileScanner {
    private static final int SAMPLES = 128;

    public static void sendBlockLootProfiles(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Map<String, BlockLootProfileS2CPayload.StateLootProfile> profiles = new LinkedHashMap<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);

            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                String stateKey = stateKey(state);

                Map<String, List<List<ItemStack>>> toolRolls = new LinkedHashMap<>();

                for (ToolProfile profile : ToolProfile.values()) {
                    ItemStack tool = toolFor(level, profile);
                    List<List<ItemStack>> rolls = scanSamples(level, player, state, tool, SAMPLES);

                    if (hasLootTable(state) || rolls.stream().anyMatch(roll -> !roll.isEmpty())) {
                        toolRolls.put(profile.name(), rolls);
                    }

                    long emptyRolls = rolls.stream().filter(List::isEmpty).count();
                    long nonEmptyRolls = rolls.size() - emptyRolls;
                    EOFDebug.log(EOFDebug.Flag.CLIENT_AUTH_ITEM,
                            "[LootProfile scan] block/state={} toolProfile={} sampleCount={} emptyRolls={} nonEmptyRolls={} exampleDrops={}",
                            stateKey,
                            profile.name(),
                            rolls.size(),
                            emptyRolls,
                            nonEmptyRolls,
                            rolls.stream().filter(roll -> !roll.isEmpty()).findFirst().orElse(List.of())
                    );
                }

                if (!toolRolls.isEmpty()) {
                    profiles.put(stateKey, new BlockLootProfileS2CPayload.StateLootProfile(blockId, stateKey, toolRolls));
                }
            }
        }

        PacketDistributor.sendToPlayer(player, new BlockLootProfileS2CPayload(profiles));

        EOFDebug.log(EOFDebug.Flag.NETWORK, 
                "[EOF LootProfile] sent {} block-state loot profiles to {}",
                profiles.size(),
                player.getGameProfile().getName()
        );
    }

    private static List<List<ItemStack>> scanSamples(
            ServerLevel level,
            ServerPlayer player,
            BlockState state,
            ItemStack tool,
            int samples
    ) {
        List<List<ItemStack>> result = new ArrayList<>();

        for (int i = 0; i < samples; i++) {
            List<ItemStack> drops = scanDrop(level, player, state, tool);

            List<ItemStack> roll = drops.stream()
                    .filter(s -> !s.isEmpty())
                    .map(ItemStack::copy)
                    .toList();

            result.add(roll);
        }

        return result;
    }

    private static List<ItemStack> scanDrop(
            ServerLevel level,
            ServerPlayer player,
            BlockState state,
            ItemStack tool
    ) {
        try {
            LootParams.Builder params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, player.position())
                    .withParameter(LootContextParams.BLOCK_STATE, state)
                    .withParameter(LootContextParams.TOOL, tool)
                    .withOptionalParameter(LootContextParams.THIS_ENTITY, player);

            return state.getDrops(params).stream()
                    .filter(s -> !s.isEmpty())
                    .map(ItemStack::copy)
                    .toList();
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static boolean hasLootTable(BlockState state) {
        return !state.getBlock().getLootTable().equals(net.minecraft.world.level.storage.loot.BuiltInLootTables.EMPTY);
    }

    private static ItemStack toolFor(ServerLevel level, ToolProfile profile) {
        var lookup = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

        return switch (profile) {
            case HAND -> ItemStack.EMPTY;
            case PICKAXE -> new ItemStack(Items.DIAMOND_PICKAXE);
            case AXE -> new ItemStack(Items.DIAMOND_AXE);
            case SHOVEL -> new ItemStack(Items.DIAMOND_SHOVEL);
            case HOE -> new ItemStack(Items.DIAMOND_HOE);
            case SHEARS -> new ItemStack(Items.SHEARS);
            case SILK_TOUCH -> {
                ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
                tool.enchant(lookup.getOrThrow(Enchantments.SILK_TOUCH), 1);
                yield tool;
            }
            case FORTUNE_1 -> {
                ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
                tool.enchant(lookup.getOrThrow(Enchantments.FORTUNE), 1);
                yield tool;
            }
            case FORTUNE_2 -> {
                ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
                tool.enchant(lookup.getOrThrow(Enchantments.FORTUNE), 2);
                yield tool;
            }
            case FORTUNE_3 -> {
                ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
                tool.enchant(lookup.getOrThrow(Enchantments.FORTUNE), 3);
                yield tool;
            }
        };
    }

    public static String stateKey(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

        if (state.getValues().isEmpty()) {
            return blockId.toString();
        }

        StringBuilder sb = new StringBuilder(blockId.toString());
        sb.append("[");

        boolean first = true;

        for (var entry : state.getValues().entrySet()) {
            if (!first) sb.append(",");
            first = false;

            sb.append(entry.getKey().getName());
            sb.append("=");
            sb.append(entry.getValue());
        }

        sb.append("]");
        return sb.toString();
    }

    public enum ToolProfile {
        HAND,
        PICKAXE,
        AXE,
        SHOVEL,
        HOE,
        SHEARS,
        SILK_TOUCH,
        FORTUNE_1,
        FORTUNE_2,
        FORTUNE_3
    }
}
