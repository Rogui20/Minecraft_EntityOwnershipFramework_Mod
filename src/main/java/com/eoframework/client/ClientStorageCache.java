package com.eoframework.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientStorageCache {
    private record Entry(List<ItemStack> items, boolean owner) {}
    private static final Map<BlockPos, Entry> CACHE = new HashMap<>();

    public static boolean isOwner(BlockPos pos) {
        Entry entry = CACHE.get(pos);
        return entry != null && entry.owner();
    }

    public static void put(BlockPos pos, List<ItemStack> items, boolean owner) {
        Entry entry = new Entry(items.stream().map(ItemStack::copy).toList(), owner);
        CACHE.put(pos.immutable(), entry);
        /*
        System.out.println("[EOF StorageCache] put pos=" + pos
                + " owner=" + owner
                + " slots=" + items.size());

         */
    }

    public static boolean has(BlockPos pos) {
        return CACHE.containsKey(pos);
    }

    public static List<ItemStack> get(BlockPos pos) {
        Entry entry = CACHE.get(pos);
        return entry == null
                ? List.of()
                : entry.items().stream().map(ItemStack::copy).toList();
    }

    public static void remove(BlockPos pos) {
        CACHE.remove(pos);
    }

    public static void clear() {
        CACHE.clear();
    }
}