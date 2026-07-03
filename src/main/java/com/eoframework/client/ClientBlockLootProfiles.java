package com.eoframework.client;

import com.eoframework.network.BlockLootProfileS2CPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class ClientBlockLootProfiles {
    private static final Map<String, BlockLootProfileS2CPayload.StateLootProfile> PROFILES = new HashMap<>();
    private static final Random RANDOM = new Random();

    public static void clear() {
        PROFILES.clear();
    }

    public static void put(String stateKey, BlockLootProfileS2CPayload.StateLootProfile profile) {
        PROFILES.put(stateKey, profile);
    }

    public static List<ItemStack> getDrops(BlockState state, ItemStack tool) {
        BlockLootProfileS2CPayload.StateLootProfile profile = PROFILES.get(stateKey(state));
        if (profile == null) {
            return List.of();
        }

        String toolProfile = selectToolProfile(tool);
        List<List<ItemStack>> rolls = profile.toolRolls().get(toolProfile);

        if ((rolls == null || rolls.isEmpty()) && !"HAND".equals(toolProfile)) {
            rolls = profile.toolRolls().get("HAND");
        }

        if (rolls == null || rolls.isEmpty()) {
            return List.of();
        }

        List<ItemStack> chosenRoll = rolls.get(RANDOM.nextInt(rolls.size()));

        return chosenRoll.stream()
                .filter(s -> !s.isEmpty())
                .map(ItemStack::copy)
                .toList();
    }

    private static String selectToolProfile(ItemStack tool) {
        if (tool.isEmpty()) return "HAND";

        Item item = tool.getItem();

        // Simples e eficiente; depois dá para trocar por canPerformAction se quiser.
        if (item == Items.SHEARS) return "SHEARS";

        // Encantamentos: se o client lookup for problemático, pode deixar só por item.
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level != null) {
                var lookup = mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);

                int silk = tool.getEnchantmentLevel(lookup.getOrThrow(Enchantments.SILK_TOUCH));
                int fortune = tool.getEnchantmentLevel(lookup.getOrThrow(Enchantments.FORTUNE));

                if (silk > 0) return "SILK_TOUCH";
                if (fortune >= 3) return "FORTUNE_3";
                if (fortune == 2) return "FORTUNE_2";
                if (fortune == 1) return "FORTUNE_1";
            }
        } catch (Throwable ignored) {
        }

        if (item instanceof PickaxeItem) return "PICKAXE";
        if (item instanceof AxeItem) return "AXE";
        if (item instanceof ShovelItem) return "SHOVEL";
        if (item instanceof HoeItem) return "HOE";

        return "HAND";
    }

    public static String stateKey(BlockState state) {
        var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

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
}