package com.eoframework.common;

import com.eoframework.EOFramework;
import com.eoframework.network.StorageInsertResultS2CPayload;
import com.eoframework.network.StoragePlaceCarriedToInventoryResultS2CPayload;
import com.eoframework.network.StorageSnapshotS2CPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
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
    private static final Map<UUID, ValidatedCarried> VALIDATED_CARRIED = new HashMap<>();
    private static long nextCarriedToken = 1L;

    private record ValidatedCarried(long token, ItemStack stack) {}

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

        EOFDebug.log(EOFDebug.Flag.STORAGE, 
                "[EOF StorageCommit] canonical={} firstNonEmptyIndexes={}",
                positions.get(0),
                firstNonEmptyIndexes(items)
        );
        EOFDebug.log(EOFDebug.Flag.STORAGE, 
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

        BlockPos first = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT ? pos.immutable() : other.immutable();
        BlockPos second = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT ? other.immutable() : pos.immutable();
        List<BlockPos> positions = List.of(first, second);

        EOFDebug.log(EOFDebug.Flag.STORAGE, 
                "[EOF StorageOrder] clickedPos={} canonical={} positions={} chestTypes=[{},{}] facing={}",
                pos,
                first,
                positions,
                level.getBlockState(first).getValue(ChestBlock.TYPE),
                level.getBlockState(second).getValue(ChestBlock.TYPE),
                state.getValue(ChestBlock.FACING)
        );

        return positions;
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
        EOFDebug.log(EOFDebug.Flag.STORAGE_SNAPSHOT, "canonical={} positions={} slots={} firstNonEmptyIndexes={}", positions.get(0), positions, items.size(), firstNonEmptyIndexes(items));
        PacketDistributor.sendToPlayer(
                player,
                new StorageSnapshotS2CPayload(positions.get(0), positions, items, owner)
        );
    }

    private static List<Integer> firstNonEmptyIndexes(List<ItemStack> items) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < items.size() && indexes.size() < 12; i++) {
            if (!items.get(i).isEmpty()) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static void sendTakeResult(ServerPlayer requester, boolean accepted, boolean quickMove, ItemStack stack, long requestId) {
        long carriedToken = 0L;
        if (accepted && !quickMove && !stack.isEmpty()) {
            carriedToken = nextCarriedToken++;
            VALIDATED_CARRIED.put(requester.getUUID(), new ValidatedCarried(carriedToken, stack.copy()));
            EOFDebug.log(EOFDebug.Flag.STORAGE_TAKE, "server generated token={} requestId={} requester={} stack={}", carriedToken, requestId, requester.getGameProfile().getName(), stack);
        }
        PacketDistributor.sendToPlayer(
                requester,
                new com.eoframework.network.StorageSlotResultS2CPayload(
                        accepted,
                        quickMove,
                        stack.copy(),
                        requestId,
                        carriedToken
                )
        );

        EOFDebug.log(EOFDebug.Flag.STORAGE, 
                "[EOF StorageTake] requestId={} operation={} server validated accepted={} quickMove={} stack={} requester={} reason=validated",
                requestId,
                quickMove ? "QUICK_TAKE" : "TAKE",
                accepted,
                quickMove,
                stack,
                requester.getGameProfile().getName() + " token=" + carriedToken
        );
    }

    private static boolean takeSlotDirectlyForNonOwner(
            ServerLevel level,
            BlockPos clickedPos,
            ServerPlayer requester,
            int slot,
            boolean quickMove,
            long requestId
    ) {
        List<BlockPos> positions = storagePositions(level, clickedPos);
        int totalSlots = collectStorageItems(level, positions).size();

        if (slot < 0 || slot >= totalSlots || isOwner(level, clickedPos, requester)) {
            sendTakeResult(requester, false, quickMove, ItemStack.EMPTY, requestId);
            List<ItemStack> fresh = collectStorageItems(level, positions);
            sendSnapshot(requester, positions, fresh, isOwner(level, clickedPos, requester));
            return false;
        }

        int index = slot;

        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container container)) continue;

            if (index < container.getContainerSize()) {
                ItemStack stack = container.getItem(index);
                if (stack.isEmpty()) {
                    sendTakeResult(requester, false, quickMove, ItemStack.EMPTY, requestId);
                    List<ItemStack> fresh = collectStorageItems(level, positions);
                    sendSnapshot(requester, positions, fresh, false);
                    return false;
                }

                ItemStack beforeContainer = stack.copy();
                EOFDebug.log(EOFDebug.Flag.STORAGE, 
                        "[EOF StorageTake] server TAKE before slot stack requestId={} slotId={} stack={}",
                        requestId,
                        slot,
                        beforeContainer
                );
                EOFDebug.log(EOFDebug.Flag.STORAGE, 
                        "[EOF StorageTake] server TAKE quickMove flag requestId={} quickMove={}",
                        requestId,
                        quickMove
                );
                ItemStack taken = stack.copy();
                boolean addInventoryResult = true;
                if (quickMove) {
                    addInventoryResult = canFullyAddToInventory(requester.getInventory(), taken);
                    if (!addInventoryResult) {
                        EOFDebug.log(EOFDebug.Flag.STORAGE, 
                                "[EOF StorageTake] requestId={} operation=QUICK_TAKE requester={} slotId={} containerBefore={} containerAfter={} accepted=false reason=inventory_full",
                                requestId,
                                requester.getGameProfile().getName(),
                                slot,
                                beforeContainer,
                                stack
                        );
                        sendTakeResult(requester, false, quickMove, ItemStack.EMPTY, requestId);
                        return false;
                    }
                    requester.getInventory().add(taken.copy());
                }

                container.setItem(index, ItemStack.EMPTY);
                container.setChanged();

                EOFDebug.log(EOFDebug.Flag.STORAGE, 
                        "[EOF StorageTake] requestId={} operation={} requester={} slotId={} containerBefore={} containerAfter=EMPTY addInventoryResult={}",
                        requestId,
                        quickMove ? "QUICK_TAKE" : "TAKE",
                        requester.getGameProfile().getName(),
                        slot,
                        beforeContainer,
                        quickMove ? addInventoryResult : "cursor"
                );
                requester.getInventory().setChanged();
                requester.containerMenu.broadcastChanges();

                EOFDebug.log(EOFDebug.Flag.STORAGE, 
                        "[EOF StorageTake] server TAKE result stack requestId={} accepted=true quickMove={} stack={}",
                        requestId,
                        quickMove,
                        taken
                );
                sendTakeResult(requester, true, quickMove, taken, requestId);
                List<ItemStack> fresh = collectStorageItems(level, positions);
                broadcastSnapshotToNearby(level, clickedPos, positions, fresh);

                return true;
            }

            index -= container.getContainerSize();
        }

        sendTakeResult(requester, false, quickMove, ItemStack.EMPTY, requestId);
        return false;
    }

    private static boolean canFullyAddToInventory(Inventory inventory, ItemStack stack) {
        int remaining = stack.getCount();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) {
                remaining -= Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize());
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                remaining -= Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize()) - existing.getCount();
            }

            if (remaining <= 0) {
                return true;
            }
        }

        return false;
    }

    public static boolean takeSlotForNonOwner(ServerLevel level, BlockPos clickedPos, ServerPlayer requester, int slot, boolean quickMove, long requestId) {
        return takeSlotDirectlyForNonOwner(level, clickedPos, requester, slot, quickMove, requestId);
    }

    public static void applyOwnerSlotResponse(
            ServerLevel level,
            ServerPlayer owner,
            UUID requesterUuid,
            BlockPos clickedPos,
            int slot,
            boolean accepted,
            boolean quickMove,
            ItemStack stack,
            long requestId
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
                            ItemStack.EMPTY,
                            requestId,
                            0L
                    )
            );

            return;
        }

        if (quickMove) {
            ItemStack copy = stack.copy();
            if (!requester.getInventory().add(copy)) {
                requester.drop(copy, false);
            }
        }

        long carriedToken = 0L;
        if (!quickMove) {
            carriedToken = nextCarriedToken++;
            VALIDATED_CARRIED.put(requester.getUUID(), new ValidatedCarried(carriedToken, stack.copy()));
            EOFDebug.log(EOFDebug.Flag.STORAGE_TAKE, "server generated token={} requestId={} requester={} stack={} source=owner_response", carriedToken, requestId, requester.getGameProfile().getName(), stack);
        }

        PacketDistributor.sendToPlayer(
                requester,
                new com.eoframework.network.StorageSlotResultS2CPayload(
                        true,
                        quickMove,
                        stack.copy(),
                        requestId,
                        carriedToken
                )
        );

        List<ItemStack> fresh = collectStorageItems(level, positions);
        broadcastSnapshotToNearby(level, clickedPos, positions, fresh);

        EOFDebug.log(EOFDebug.Flag.STORAGE, 
                "[EOF Storage] owner approved/denied slot={} accepted=true item={} requester={} owner={}",
                slot,
                stack,
                requester.getGameProfile().getName() + " token=" + carriedToken,
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
            ItemStack offered,
            long requestId,
            long carriedToken
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
                    offered,
                    requestId,
                    carriedToken,
                    false
            )) {
                return true;
            }
        }

        PacketDistributor.sendToPlayer(
                requester,
                new StorageInsertResultS2CPayload(false, 0, sourceSlot, requestId, carriedToken)
        );

        return false;
    }

    private static int menuSlotIdToPlayerInventoryIndex(int menuSlotId, int storageSlots) {
        if (menuSlotId >= storageSlots && menuSlotId < storageSlots + 27) {
            return 9 + (menuSlotId - storageSlots);
        }

        if (menuSlotId >= storageSlots + 27 && menuSlotId < storageSlots + 36) {
            return menuSlotId - (storageSlots + 27);
        }

        return -1;
    }

    private static void logStorageInsertTokenDecision(
            ServerPlayer requester,
            int targetSlot,
            int sourceSlot,
            ItemStack offered,
            long requestId,
            long carriedToken,
            ValidatedCarried validated,
            String invalidReason
    ) {
        EOFDebug.log(
                EOFDebug.Flag.STORAGE_INSERT,
                "server token decision accepted={} reason={} token={} requester={} targetSlot={} sourceSlot={} stack={} carriedTokenExists={} carriedTokenOwner={} carriedTokenConsumed={} tokenStackExpected={}",
                invalidReason == null,
                invalidReason == null ? "VALID" : invalidReason,
                carriedToken,
                requester.getGameProfile().getName(),
                targetSlot,
                sourceSlot,
                offered,
                validated != null && validated.token() == carriedToken,
                requester.getUUID(),
                validated == null,
                validated == null ? ItemStack.EMPTY : validated.stack()
        );
    }

    private static void logStorageInsertDenied(
            ServerPlayer requester,
            int targetSlot,
            int sourceSlot,
            int storageSlots,
            ItemStack offered,
            long requestId,
            long carriedToken,
            String reason
    ) {
        ValidatedCarried validated = sourceSlot < 0 ? VALIDATED_CARRIED.get(requester.getUUID()) : null;
        EOFDebug.log(
                EOFDebug.Flag.STORAGE_INSERT,
                "server deny reason={} token={} requester={} targetSlot={} sourceSlot={} storageSlots={} stack={} carriedTokenExists={} carriedTokenOwner={} carriedTokenConsumed={} tokenStackExpected={} requestId={}",
                reason,
                carriedToken,
                requester.getGameProfile().getName(),
                targetSlot,
                sourceSlot,
                storageSlots,
                offered,
                validated != null && validated.token() == carriedToken,
                requester.getUUID(),
                sourceSlot < 0 && validated == null,
                validated == null ? ItemStack.EMPTY : validated.stack(),
                requestId
        );
    }

    private static ItemStack getPlayerInventorySourceStack(ServerPlayer player, int menuSlotId, int storageSlots) {
        int invIndex = menuSlotIdToPlayerInventoryIndex(menuSlotId, storageSlots);
        if (invIndex < 0 || invIndex >= player.getInventory().getContainerSize()) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getItem(invIndex);
    }

    private static void removeFromPlayerInventorySlot(
            ServerPlayer player,
            int menuSlotId,
            int storageSlots,
            ItemStack expected,
            int count
    ) {
        int invIndex = menuSlotIdToPlayerInventoryIndex(menuSlotId, storageSlots);
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

    public static boolean insertSlotForNonOwner(
            ServerLevel level,
            BlockPos clickedPos,
            ServerPlayer requester,
            int slot,
            int sourceSlot,
            int storageSlots,
            ItemStack offered,
            long requestId,
            long carriedToken
    ) {
        return insertSlotForNonOwner(level, clickedPos, requester, slot, sourceSlot, storageSlots, offered, requestId, carriedToken, true);
    }

    private static boolean insertSlotForNonOwner(
            ServerLevel level,
            BlockPos clickedPos,
            ServerPlayer requester,
            int slot,
            int sourceSlot,
            int storageSlots,
            ItemStack offered,
            long requestId,
            long carriedToken,
            boolean notifyFailure
    ) {
        if (offered.isEmpty()) {
            logStorageInsertDenied(requester, slot, sourceSlot, storageSlots, offered, requestId, carriedToken, "SOURCE_SLOT_EMPTY");
            if (notifyFailure) {
                PacketDistributor.sendToPlayer(
                        requester,
                        new com.eoframework.network.StorageInsertResultS2CPayload(false, 0, sourceSlot, requestId, carriedToken)
                );
            }
            return false;
        }

        List<BlockPos> positions = storagePositions(level, clickedPos);
        if (isOwner(level, clickedPos, requester)) {
            logStorageInsertDenied(requester, slot, sourceSlot, storageSlots, offered, requestId, carriedToken, "OWNER_CANNOT_USE_NON_OWNER_PATH");
            if (notifyFailure) {
                PacketDistributor.sendToPlayer(
                        requester,
                        new com.eoframework.network.StorageInsertResultS2CPayload(false, 0, sourceSlot, requestId, carriedToken)
                );
            }
            return false;
        }

        if (sourceSlot < 0) {
            ValidatedCarried validated = VALIDATED_CARRIED.get(requester.getUUID());
            String invalidReason = null;
            if (carriedToken == 0L) invalidReason = "TOKEN_NOT_FOUND";
            else if (validated == null) invalidReason = "TOKEN_CONSUMED";
            else if (validated.token() != carriedToken) invalidReason = "TOKEN_NOT_FOUND";
            else if (!ItemStack.isSameItemSameComponents(validated.stack(), offered) || validated.stack().getCount() != offered.getCount()) invalidReason = "STACK_MISMATCH";
            logStorageInsertTokenDecision(requester, slot, sourceSlot, offered, requestId, carriedToken, validated, invalidReason);
            if (invalidReason != null) {
                if (notifyFailure) {
                    PacketDistributor.sendToPlayer(requester, new com.eoframework.network.StorageInsertResultS2CPayload(false, 0, sourceSlot, requestId, carriedToken));
                }
                return false;
            }
        }

        if (slot < 0) {
            return insertIntoAnyStorageSlot(
                    level,
                    clickedPos,
                    requester,
                    sourceSlot,
                    storageSlots,
                    offered,
                    requestId,
                    carriedToken
            );
        }

        int invIndex = menuSlotIdToPlayerInventoryIndex(sourceSlot, storageSlots);
        ItemStack beforeSource = ItemStack.EMPTY;
        ItemStack sourceBackedOffer = offered.copy();
        if (sourceSlot >= 0) {
            ItemStack actualSource = getPlayerInventorySourceStack(requester, sourceSlot, storageSlots);
            beforeSource = actualSource.copy();
            if (actualSource.isEmpty() || !ItemStack.isSameItemSameComponents(actualSource, offered)) {
                logStorageInsertDenied(requester, slot, sourceSlot, storageSlots, offered, requestId, carriedToken, actualSource.isEmpty() ? "SOURCE_SLOT_EMPTY" : "STACK_MISMATCH");
                if (notifyFailure) {
                    PacketDistributor.sendToPlayer(
                            requester,
                            new com.eoframework.network.StorageInsertResultS2CPayload(false, 0, sourceSlot, requestId, carriedToken)
                    );
                }
                EOFDebug.log(EOFDebug.Flag.STORAGE, 
                        "[EOF StorageInsert] requestId={} operation=INSERT requester={} sourceSlot={} storageSlots={} invIndex={} before={} inserted=0 after={} reason=source_mismatch",
                        requestId,
                        requester.getGameProfile().getName(),
                        sourceSlot,
                        storageSlots,
                        invIndex,
                        beforeSource,
                        beforeSource
                );
                return false;
            }
            sourceBackedOffer = actualSource.copyWithCount(Math.min(offered.getCount(), actualSource.getCount()));
        }

        int index = slot;

        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container container)) continue;

            if (index < container.getContainerSize()) {
                ItemStack existing = container.getItem(index);
                ItemStack toInsert = sourceBackedOffer.copy();

                int inserted = 0;

                if (existing.isEmpty()) {
                    int amount = Math.min(toInsert.getCount(), toInsert.getMaxStackSize());
                    if (sourceSlot < 0 && amount < toInsert.getCount()) {
                        logStorageInsertDenied(requester, slot, sourceSlot, storageSlots, offered, requestId, carriedToken, "NO_SPACE");
                        if (notifyFailure) {
                            PacketDistributor.sendToPlayer(
                                    requester,
                                    new com.eoframework.network.StorageInsertResultS2CPayload(false, 0, sourceSlot, requestId, carriedToken)
                            );
                        }
                        return false;
                    }
                    ItemStack placed = toInsert.copyWithCount(amount);

                    container.setItem(index, placed);
                    inserted = amount;
                } else if (ItemStack.isSameItemSameComponents(existing, toInsert)) {
                    int max = Math.min(existing.getMaxStackSize(), container.getMaxStackSize());
                    int room = max - existing.getCount();
                    if (sourceSlot < 0 && room < toInsert.getCount()) {
                        logStorageInsertDenied(requester, slot, sourceSlot, storageSlots, offered, requestId, carriedToken, "NO_SPACE");
                        if (notifyFailure) {
                            PacketDistributor.sendToPlayer(
                                    requester,
                                    new com.eoframework.network.StorageInsertResultS2CPayload(false, 0, sourceSlot, requestId, carriedToken)
                            );
                        }
                        return false;
                    }

                    if (room > 0) {
                        inserted = Math.min(room, toInsert.getCount());
                        existing.grow(inserted);
                        container.setItem(index, existing);
                    }
                }

                if (inserted <= 0) {
                    logStorageInsertDenied(requester, slot, sourceSlot, storageSlots, offered, requestId, carriedToken, "SLOT_REJECTED");
                    if (notifyFailure) {
                        PacketDistributor.sendToPlayer(
                                requester,
                                new com.eoframework.network.StorageInsertResultS2CPayload(false, 0, sourceSlot, requestId, carriedToken)
                        );
                    }
                    return false;
                }

                container.setChanged();
                if (sourceSlot >= 0) {
                    removeFromPlayerInventorySlot(requester, sourceSlot, storageSlots, sourceBackedOffer, inserted);
                }

                if (sourceSlot < 0) {
                    VALIDATED_CARRIED.remove(requester.getUUID());
                    EOFDebug.log(EOFDebug.Flag.STORAGE_INSERT, "server token consumed reason=INSERT_ACCEPTED token={} requestId={} requester={}", carriedToken, requestId, requester.getGameProfile().getName());
                }

                PacketDistributor.sendToPlayer(
                        requester,
                        new com.eoframework.network.StorageInsertResultS2CPayload(true, inserted, sourceSlot, requestId, carriedToken)
                );

                List<ItemStack> fresh = collectStorageItems(level, positions);
                broadcastSnapshotToNearby(level, clickedPos, positions, fresh);

                ItemStack afterSource = sourceSlot >= 0
                        ? getPlayerInventorySourceStack(requester, sourceSlot, storageSlots).copy()
                        : requester.containerMenu.getCarried().copy();
                EOFDebug.log(EOFDebug.Flag.STORAGE, 
                        "[EOF StorageInsert] requestId={} operation={} requester={} sourceSlot={} storageSlots={} invIndex={} before={} inserted={} after={} containerSlot={} containerBefore={} containerAfter={} accepted=true",
                        requestId,
                        sourceSlot >= 0 ? "QUICK_INSERT" : "INSERT",
                        requester.getGameProfile().getName(),
                        sourceSlot,
                        storageSlots,
                        invIndex,
                        beforeSource,
                        inserted,
                        afterSource,
                        slot,
                        existing,
                        container.getItem(index)
                );

                return true;
            }

            index -= container.getContainerSize();
        }

        if (notifyFailure) {
            logStorageInsertDenied(requester, slot, sourceSlot, storageSlots, offered, requestId, carriedToken, "SLOT_REJECTED");
            PacketDistributor.sendToPlayer(
                    requester,
                    new com.eoframework.network.StorageInsertResultS2CPayload(false, 0, sourceSlot, requestId, carriedToken)
            );
        }

        return false;
    }

    public static boolean placeCarriedToInventoryForNonOwner(
            ServerLevel level,
            BlockPos clickedPos,
            ServerPlayer requester,
            int targetSlot,
            int storageSlots,
            ItemStack offered,
            long requestId
    ) {
        int invIndex = menuSlotIdToPlayerInventoryIndex(targetSlot, storageSlots);
        String requesterName = requester.getGameProfile().getName();

        if (offered.isEmpty() || invIndex < 0 || invIndex >= requester.getInventory().getContainerSize()) {
            logPlaceInventoryDenied(requestId, requesterName, targetSlot, -1, storageSlots, invIndex, requester.containerMenu.getCarried(), offered, "empty_offered_or_bad_inventory_slot");
            PacketDistributor.sendToPlayer(requester, new StoragePlaceCarriedToInventoryResultS2CPayload(false, 0, targetSlot, requestId));
            return false;
        }

        if (isOwner(level, clickedPos, requester)) {
            logPlaceInventoryDenied(requestId, requesterName, targetSlot, -1, storageSlots, invIndex, requester.containerMenu.getCarried(), offered, "owner_cannot_use_non_owner_path");
            PacketDistributor.sendToPlayer(requester, new StoragePlaceCarriedToInventoryResultS2CPayload(false, 0, targetSlot, requestId));
            return false;
        }

        ItemStack existing = requester.getInventory().getItem(invIndex);
        int placed;
        if (existing.isEmpty()) {
            placed = Math.min(offered.getCount(), offered.getMaxStackSize());
            requester.getInventory().setItem(invIndex, offered.copyWithCount(placed));
        } else if (ItemStack.isSameItemSameComponents(existing, offered)) {
            int room = Math.min(existing.getMaxStackSize(), requester.getInventory().getMaxStackSize()) - existing.getCount();
            placed = Math.min(room, offered.getCount());
            if (placed <= 0) {
                logPlaceInventoryDenied(requestId, requesterName, targetSlot, -1, storageSlots, invIndex, requester.containerMenu.getCarried(), offered, "target_full");
                PacketDistributor.sendToPlayer(requester, new StoragePlaceCarriedToInventoryResultS2CPayload(false, 0, targetSlot, requestId));
                return false;
            }
            existing.grow(placed);
            requester.getInventory().setItem(invIndex, existing);
        } else {
            logPlaceInventoryDenied(requestId, requesterName, targetSlot, -1, storageSlots, invIndex, requester.containerMenu.getCarried(), offered, "target_mismatch");
            PacketDistributor.sendToPlayer(requester, new StoragePlaceCarriedToInventoryResultS2CPayload(false, 0, targetSlot, requestId));
            return false;
        }

        requester.getInventory().setChanged();
        requester.containerMenu.broadcastChanges();
        ValidatedCarried validated = VALIDATED_CARRIED.get(requester.getUUID());
        if (validated != null && ItemStack.isSameItemSameComponents(validated.stack(), offered) && validated.stack().getCount() == offered.getCount()) {
            VALIDATED_CARRIED.remove(requester.getUUID());
            EOFDebug.log(EOFDebug.Flag.STORAGE_INSERT, "server token consumed reason=PLACE_TO_INVENTORY_ACCEPTED token={} requestId={} requester={}", validated.token(), requestId, requesterName);
        }
        PacketDistributor.sendToPlayer(requester, new StoragePlaceCarriedToInventoryResultS2CPayload(true, placed, targetSlot, requestId));
        EOFDebug.log(EOFDebug.Flag.STORAGE, 
                "[EOF StoragePlaceInventory] operation=PLACE_CARRIED_TO_INVENTORY requestId={} pendingOperation=server carried={} carriedFromStorageValidated=server_validated_cursor sourceSlot=-1 storageSlots={} invIndex={} targetSlot={} requester={} placed={} accepted=true reason=placed",
                requestId,
                requester.containerMenu.getCarried(),
                storageSlots,
                invIndex,
                targetSlot,
                requesterName,
                placed
        );
        return true;
    }

    private static void logPlaceInventoryDenied(long requestId, String requesterName, int targetSlot, int sourceSlot, int storageSlots, int invIndex, ItemStack carried, ItemStack offered, String reason) {
        EOFDebug.log(EOFDebug.Flag.STORAGE, 
                "[EOF StorageDeny] operation=PLACE_CARRIED_TO_INVENTORY requestId={} pendingOperation=server carried={} carriedFromStorageValidated=server_validated_cursor sourceSlot={} storageSlots={} invIndex={} targetSlot={} requester={} offered={} reason={}",
                requestId,
                carried,
                sourceSlot,
                storageSlots,
                invIndex,
                targetSlot,
                requesterName,
                offered,
                reason
        );
    }

    private static class StorageSession {
        UUID owner;
        boolean openByOwner;

        StorageSession(UUID owner) {
            this.owner = owner;
        }
    }
}
