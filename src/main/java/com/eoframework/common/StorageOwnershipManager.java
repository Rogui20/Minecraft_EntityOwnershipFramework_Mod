package com.eoframework.common;

import com.eoframework.EOFramework;
import com.eoframework.network.StorageInsertResultS2CPayload;
import com.eoframework.network.StorageSnapshotS2CPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public class StorageOwnershipManager {
    private static final int SCAN_RADIUS = 8;
    private static final int SNAPSHOT_INTERVAL = 5;

    private static int tickCounter = 0;
    private static final Map<GlobalPos, StorageSession> SESSIONS = new HashMap<>();

    public static void tick(ServerLevel level) {
        tickCounter++;

        if (tickCounter % SNAPSHOT_INTERVAL != 0) return;

        for (ServerPlayer player : level.players()) {
            scanAroundPlayer(level, player);
        }
    }

    private static void scanAroundPlayer(ServerLevel level, ServerPlayer player) {
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Set<GlobalPos> processedStorages = new HashSet<>();

        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);

                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof Container)) continue;

                    List<BlockPos> positions = storagePositions(level, pos.immutable());
                    GlobalPos key = GlobalPos.of(level.dimension(), positions.get(0));

                    if (!processedStorages.add(key)) {
                        continue;
                    }

                    List<ItemStack> items = collectStorageItems(level, positions);
                    boolean ownerView = isOwner(level, positions.get(0), player);
                    sendSnapshot(player, positions, items, ownerView);
                }
            }
        }
    }

    public static UUID getOwner(ServerLevel level, BlockPos pos) {
        List<BlockPos> positions = storagePositions(level, pos);
        BlockPos canonical = positions.get(0);
        StorageSession session = SESSIONS.get(GlobalPos.of(level.dimension(), canonical));

        if (session != null && session.openByOwner) {
            ServerPlayer pinnedOwner = level.getServer().getPlayerList().getPlayer(session.owner);
            if (pinnedOwner != null) {
                return session.owner;
            }
        }

        return ChunkOwnershipManager.getOwner(level, canonical);
    }

    public static boolean isOwner(ServerLevel level, BlockPos pos, ServerPlayer player) {
        UUID owner = getOwner(level, pos);
        if (owner == null) {
            owner = ChunkOwnershipManager.getOrAssignOwner(level, storagePositions(level, pos).get(0), player);
        }
        return player.getUUID().equals(owner);
    }

    private static StorageSession sessionFor(ServerLevel level, BlockPos clickedPos) {
        List<BlockPos> positions = storagePositions(level, clickedPos);
        return SESSIONS.get(GlobalPos.of(level.dimension(), positions.get(0)));
    }

    private static void broadcastSnapshotToNearbyExcept(
            ServerLevel level,
            BlockPos clickedPos,
            List<BlockPos> positions,
            List<ItemStack> items,
            UUID except
    ) {
        for (ServerPlayer p : level.players()) {
            if (p.getUUID().equals(except)) continue;

            if (p.distanceToSqr(
                    clickedPos.getX() + 0.5D,
                    clickedPos.getY() + 0.5D,
                    clickedPos.getZ() + 0.5D
            ) > 16.0D * 16.0D) {
                continue;
            }

            sendSnapshot(p, positions, items, isOwner(level, clickedPos, p));
        }
    }

    public static boolean applySnapshot(ServerLevel level, BlockPos clickedPos, ServerPlayer player, List<ItemStack> items) {
        List<BlockPos> positions = storagePositions(level, clickedPos);

        if (!isOwner(level, clickedPos, player)) {
            EOFramework.LOGGER.warn(
                    "[EOF Storage] commit denied, not owner pos={} player={}",
                    clickedPos,
                    player.getGameProfile().getName()
            );
            return false;
        }

        int sourceIndex = 0;

        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container container)) continue;

            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = sourceIndex < items.size()
                        ? items.get(sourceIndex).copy()
                        : ItemStack.EMPTY;

                container.setItem(slot, stack);
                sourceIndex++;
            }

            container.setChanged();

            if (be instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
                level.sendBlockUpdated(pos, blockEntity.getBlockState(), blockEntity.getBlockState(), 3);
            }
        }

        List<ItemStack> fresh = collectStorageItems(level, positions);
        broadcastSnapshotToNearbyExcept(level, clickedPos, positions, fresh, player.getUUID());

        EOFramework.LOGGER.info(
                "[EOF Storage] commit applied pos={} slots={} player={}",
                clickedPos,
                items.size(),
                player.getGameProfile().getName()
        );

        return true;
    }

    public static void release(ServerLevel level, BlockPos pos, ServerPlayer player) {
        List<BlockPos> positions = storagePositions(level, pos);
        GlobalPos key = GlobalPos.of(level.dimension(), positions.get(0));
        StorageSession session = SESSIONS.get(key);

        if (session != null && session.owner.equals(player.getUUID())) {
            session.openByOwner = false;
        }
    }

    private static void broadcastSnapshotToNearby(
            ServerLevel level,
            BlockPos clickedPos,
            List<BlockPos> positions,
            List<ItemStack> items
    ) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(
                    clickedPos.getX() + 0.5D,
                    clickedPos.getY() + 0.5D,
                    clickedPos.getZ() + 0.5D
            ) > 16.0D * 16.0D) {
                continue;
            }

            sendSnapshot(p, positions, items, isOwner(level, clickedPos, p));
        }
    }

    public static List<BlockPos> storagePositions(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (!(state.getBlock() instanceof ChestBlock)) {
            return List.of(pos.immutable());
        }

        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return List.of(pos.immutable());
        }

        BlockPos other = findConnectedChestPos(level, pos, state);
        if (other == null) {
            return List.of(pos.immutable());
        }

        List<BlockPos> positions = new ArrayList<>();
        positions.add(pos.immutable());
        positions.add(other.immutable());
        positions.sort(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(positions);
    }

    private static BlockPos findConnectedChestPos(ServerLevel level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(ChestBlock.FACING);

        for (Direction dir : new Direction[]{facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockPos other = pos.relative(dir);
            BlockState otherState = level.getBlockState(other);

            if (otherState.getBlock() != state.getBlock()) continue;
            if (!otherState.hasProperty(ChestBlock.TYPE)) continue;
            if (!otherState.hasProperty(ChestBlock.FACING)) continue;
            if (otherState.getValue(ChestBlock.TYPE) == ChestType.SINGLE) continue;
            if (otherState.getValue(ChestBlock.FACING) != facing) continue;

            return other.immutable();
        }

        return null;
    }

    private static List<ItemStack> collectStorageItems(ServerLevel level, List<BlockPos> positions) {
        List<ItemStack> items = new ArrayList<>();

        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);

            if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    items.add(container.getItem(i).copy());
                }
            }
        }

        return items;
    }

    private static void sendSnapshot(ServerPlayer player, List<BlockPos> positions, List<ItemStack> items, boolean owner) {
        for (BlockPos pos : positions) {
            PacketDistributor.sendToPlayer(
                    player,
                    new StorageSnapshotS2CPayload(positions.get(0), positions, items, owner)
            );
        }
    }

    private static boolean takeSlotDirectlyForNonOwner(
            ServerLevel level,
            BlockPos clickedPos,
            ServerPlayer requester,
            int slot,
            boolean quickMove
    ) {
        List<BlockPos> positions = storagePositions(level, clickedPos);

        int index = slot;

        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container container)) continue;

            if (index < container.getContainerSize()) {
                ItemStack stack = container.getItem(index);
                if (stack.isEmpty()) return false;

                ItemStack taken = stack.copy();
                container.setItem(index, ItemStack.EMPTY);
                container.setChanged();

                if (!requester.getInventory().add(taken.copy())) {
                    requester.drop(taken.copy(), false);
                }

                List<ItemStack> fresh = collectStorageItems(level, positions);
                broadcastSnapshotToNearby(level, clickedPos, positions, fresh);

                PacketDistributor.sendToPlayer(
                        requester,
                        new com.eoframework.network.StorageSlotResultS2CPayload(
                                true,
                                quickMove,
                                taken.copy()
                        )
                );

                return true;
            }

            index -= container.getContainerSize();
        }

        return false;
    }

    public static boolean takeSlotForNonOwner(ServerLevel level, BlockPos clickedPos, ServerPlayer requester, int slot, boolean quickMove) {
        List<BlockPos> positions = storagePositions(level, clickedPos);
        GlobalPos key = GlobalPos.of(level.dimension(), positions.get(0));
        StorageSession session = SESSIONS.get(key);

        UUID ownerUuid = getOwner(level, clickedPos);
        if (ownerUuid == null) {
            List<ItemStack> fresh = collectStorageItems(level, positions);
            sendSnapshot(requester, positions, fresh, isOwner(level, clickedPos, requester));
            return false;
        }

        if (ownerUuid.equals(requester.getUUID())) {
            return false;
        }

        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid);
        if (owner == null || session == null || !session.openByOwner) {
            return takeSlotDirectlyForNonOwner(level, clickedPos, requester, slot, quickMove);
        }

        PacketDistributor.sendToPlayer(
                owner,
                new com.eoframework.network.StorageSlotRequestS2CPayload(
                        requester.getUUID(),
                        clickedPos.immutable(),
                        slot,
                        quickMove
                )
        );

        EOFramework.LOGGER.info(
                "[EOF Storage] forwarded non-owner slot request pos={} slot={} requester={} owner={}",
                clickedPos,
                slot,
                requester.getGameProfile().getName(),
                owner.getGameProfile().getName()
        );

        return true;
    }

    public static void applyOwnerSlotResponse(
            ServerLevel level,
            ServerPlayer owner,
            UUID requesterUuid,
            BlockPos clickedPos,
            boolean accepted,
            boolean quickMove,
            ItemStack stack
    ) {
        if (!isOwner(level, clickedPos, owner)) {
            return;
        }

        List<BlockPos> positions = storagePositions(level, clickedPos);

        ServerPlayer requester = level.getServer().getPlayerList().getPlayer(requesterUuid);
        if (requester == null) return;

        if (!accepted || stack.isEmpty()) {
            List<ItemStack> fresh = collectStorageItems(level, positions);
            sendSnapshot(requester, positions, fresh, false);

            PacketDistributor.sendToPlayer(
                    requester,
                    new com.eoframework.network.StorageSlotResultS2CPayload(
                            false,
                            quickMove,
                            ItemStack.EMPTY
                    )
            );

            return;
        }

        ItemStack copy = stack.copy();

        if (!requester.getInventory().add(copy)) {
            requester.drop(copy, false);
        }

        PacketDistributor.sendToPlayer(
                requester,
                new com.eoframework.network.StorageSlotResultS2CPayload(
                        true,
                        quickMove,
                        stack.copy()
                )
        );

        List<ItemStack> fresh = collectStorageItems(level, positions);
        broadcastSnapshotToNearby(level, clickedPos, positions, fresh);

        EOFramework.LOGGER.info(
                "[EOF Storage] owner approved slot item={} requester={} owner={}",
                stack,
                requester.getGameProfile().getName(),
                owner.getGameProfile().getName()
        );
    }

    public static void setOwnerOpen(ServerLevel level, BlockPos pos, ServerPlayer player, boolean open) {
        List<BlockPos> positions = storagePositions(level, pos);
        GlobalPos key = GlobalPos.of(level.dimension(), positions.get(0));

        if (open) {
            if (isOwner(level, pos, player)) {
                StorageSession session = SESSIONS.computeIfAbsent(key, ignored -> new StorageSession(player.getUUID()));
                session.owner = player.getUUID();
                session.openByOwner = true;
            }
            return;
        }

        StorageSession session = SESSIONS.get(key);
        if (session != null && session.owner.equals(player.getUUID())) {
            session.openByOwner = false;
            SESSIONS.remove(key);
        }
    }

    private static boolean isLockedByOwner(ServerLevel level, BlockPos pos, ServerPlayer requester) {
        List<BlockPos> positions = storagePositions(level, pos);
        StorageSession session = SESSIONS.get(GlobalPos.of(level.dimension(), positions.get(0)));

        return session != null
                && session.openByOwner
                && !session.owner.equals(requester.getUUID());
    }

    private static void removeFromPlayerInventory(ServerPlayer player, ItemStack stack, int count) {
        int remaining = count;

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack inv = player.getInventory().getItem(i);

            if (inv.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(inv, stack)) continue;

            int take = Math.min(remaining, inv.getCount());
            inv.shrink(take);

            if (inv.isEmpty()) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }

            remaining -= take;
            if (remaining <= 0) break;
        }

        player.getInventory().setChanged();
    }

    private static boolean insertIntoAnyStorageSlot(
            ServerLevel level,
            BlockPos clickedPos,
            ServerPlayer requester,
            int sourceSlot,
            int storageSlots,
            ItemStack offered
    ) {
        List<BlockPos> positions = storagePositions(level, clickedPos);
        int totalSlots = collectStorageItems(level, positions).size();

        for (int globalSlot = 0; globalSlot < totalSlots; globalSlot++) {
            if (insertSlotForNonOwner(
                    level,
                    clickedPos,
                    requester,
                    globalSlot,
                    sourceSlot,
                    storageSlots,
                    offered
            )) {
                return true;
            }
        }

        PacketDistributor.sendToPlayer(
                requester,
                new StorageInsertResultS2CPayload(false, 0)
        );

        return false;
    }

    private static void removeFromPlayerInventorySlot(
            ServerPlayer player,
            int menuSlotId,
            int storageSlots,
            ItemStack expected,
            int count
    ) {
        if (menuSlotId < storageSlots) {
            return;
        }

        int invIndex = -1;

        // Slots 0-26 do inventário principal.
        if (menuSlotId >= storageSlots && menuSlotId < storageSlots + 27) {
            invIndex = 9 + (menuSlotId - storageSlots);
        }

        // Hotbar.
        if (menuSlotId >= storageSlots + 27 && menuSlotId < storageSlots + 36) {
            invIndex = menuSlotId - (storageSlots + 27);
        }

        if (invIndex < 0 || invIndex >= player.getInventory().getContainerSize()) {
            return;
        }

        ItemStack inv = player.getInventory().getItem(invIndex);

        if (inv.isEmpty() || !ItemStack.isSameItemSameComponents(inv, expected)) {
            return;
        }

        int removed = Math.min(count, inv.getCount());
        inv.shrink(removed);

        if (inv.isEmpty()) {
            player.getInventory().setItem(invIndex, ItemStack.EMPTY);
        }

        player.getInventory().setChanged();

        player.containerMenu.broadcastChanges();
    }

    private static int menuSlotIdToPlayerInventoryIndex(int menuSlotId) {
        // Baú 6 linhas: storageSlots=54. Mas aqui precisamos saber rows.
        // Como sourceSlot veio do menu, o mapeamento genérico é difícil sem rows.
        // Para baú grande que você está testando:
        int storageSlots = 54;

        if (menuSlotId >= storageSlots && menuSlotId < storageSlots + 27) {
            return 9 + (menuSlotId - storageSlots);
        }

        if (menuSlotId >= storageSlots + 27 && menuSlotId < storageSlots + 36) {
            return menuSlotId - (storageSlots + 27);
        }

        return -1;
    }

    public static boolean insertSlotForNonOwner(
            ServerLevel level,
            BlockPos clickedPos,
            ServerPlayer requester,
            int slot,
            int sourceSlot,
            int storageSlots,
            ItemStack offered
    ) {
        if (offered.isEmpty()) {
            PacketDistributor.sendToPlayer(
                    requester,
                    new com.eoframework.network.StorageInsertResultS2CPayload(false, 0)
            );
            return false;
        }

        List<BlockPos> positions = storagePositions(level, clickedPos);
        GlobalPos key = GlobalPos.of(level.dimension(), positions.get(0));
        StorageSession session = SESSIONS.get(key);

        UUID ownerUuid = getOwner(level, clickedPos);
        if (ownerUuid != null && ownerUuid.equals(requester.getUUID())) {
            return false;
        }

        ServerPlayer owner = ownerUuid == null ? null : level.getServer().getPlayerList().getPlayer(ownerUuid);

        // Etapa atual: se owner estiver com menu aberto, por segurança nega insert.
        // Depois podemos criar request de insert para o owner validar localmente.
        if (owner != null && session.openByOwner) {
            List<ItemStack> fresh = collectStorageItems(level, positions);
            sendSnapshot(requester, positions, fresh, false);

            PacketDistributor.sendToPlayer(
                    requester,
                    new com.eoframework.network.StorageInsertResultS2CPayload(false, 0)
            );
            return false;
        }

        if (slot < 0) {
            return insertIntoAnyStorageSlot(
                    level,
                    clickedPos,
                    requester,
                    sourceSlot,
                    storageSlots,
                    offered
            );
        }

        int index = slot;

        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container container)) continue;

            if (index < container.getContainerSize()) {
                ItemStack existing = container.getItem(index);
                ItemStack toInsert = offered.copy();

                int inserted = 0;

                if (existing.isEmpty()) {
                    int amount = Math.min(toInsert.getCount(), toInsert.getMaxStackSize());
                    ItemStack placed = toInsert.copyWithCount(amount);

                    container.setItem(index, placed);
                    inserted = amount;
                } else if (ItemStack.isSameItemSameComponents(existing, toInsert)) {
                    int max = Math.min(existing.getMaxStackSize(), container.getMaxStackSize());
                    int room = max - existing.getCount();

                    if (room > 0) {
                        inserted = Math.min(room, toInsert.getCount());
                        existing.grow(inserted);
                        container.setItem(index, existing);
                    }
                }

                if (inserted <= 0) {
                    PacketDistributor.sendToPlayer(
                            requester,
                            new com.eoframework.network.StorageInsertResultS2CPayload(false, 0)
                    );
                    return false;
                }

                container.setChanged();
                removeFromPlayerInventorySlot(requester, sourceSlot, storageSlots, offered, inserted);

                List<ItemStack> fresh = collectStorageItems(level, positions);
                broadcastSnapshotToNearby(level, clickedPos, positions, fresh);

                PacketDistributor.sendToPlayer(
                        requester,
                        new com.eoframework.network.StorageInsertResultS2CPayload(true, inserted)
                );

                EOFramework.LOGGER.info(
                        "[EOF Storage] non-owner inserted item={} count={} pos={} slot={} player={}",
                        offered,
                        inserted,
                        clickedPos,
                        slot,
                        requester.getGameProfile().getName()
                );

                return true;
            }

            index -= container.getContainerSize();
        }

        PacketDistributor.sendToPlayer(
                requester,
                new com.eoframework.network.StorageInsertResultS2CPayload(false, 0)
        );

        return false;
    }

    private static class StorageSession {
        UUID owner;
        boolean openByOwner;

        StorageSession(UUID owner) {
            this.owner = owner;
        }
    }
}