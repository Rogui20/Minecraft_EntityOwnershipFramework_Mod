package com.eoframework.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientStorageCache {
    private record Entry(BlockPos canonicalPos, List<BlockPos> positions, List<ItemStack> items, boolean owner) {}
    private static final Map<BlockPos, Entry> CACHE = new HashMap<>();

    private static Entry entry(BlockPos pos) {
        return CACHE.get(pos.immutable());
    }

    public static BlockPos canonicalPos(BlockPos pos) {
        Entry entry = entry(pos);
        return entry == null ? pos.immutable() : entry.canonicalPos();
    }

    public static List<BlockPos> positions(BlockPos pos) {
        Entry entry = entry(pos);
        return entry == null ? List.of(pos.immutable()) : entry.positions();
    }

    public static boolean isOwner(BlockPos pos) {
        Entry entry = entry(pos);
        return entry != null && entry.owner();
    }

    public static void put(BlockPos canonicalPos, List<BlockPos> positions, List<ItemStack> items, boolean owner) {
        BlockPos canonical = canonicalPos.immutable();
        List<BlockPos> aliases = positions.stream().map(BlockPos::immutable).toList();
        Entry entry = new Entry(
                canonical,
                aliases,
                items.stream().map(ItemStack::copy).toList(),
                owner
        );
        CACHE.put(canonical, entry);
        for (BlockPos pos : aliases) {
            CACHE.put(pos.immutable(), entry);
        }
    }

    public static boolean has(BlockPos pos) {
        return CACHE.containsKey(pos.immutable());
    }

    public static List<ItemStack> get(BlockPos pos) {
        Entry entry = entry(pos);
        return entry == null
                ? List.of()
                : entry.items().stream().map(ItemStack::copy).toList();
    }

    public static void remove(BlockPos pos) {
        Entry entry = entry(pos);
        if (entry == null) return;
        CACHE.remove(entry.canonicalPos());
        for (BlockPos alias : entry.positions()) {
            CACHE.remove(alias);
        }
    }

    public static void clear() {
        CACHE.clear();
    }
}
