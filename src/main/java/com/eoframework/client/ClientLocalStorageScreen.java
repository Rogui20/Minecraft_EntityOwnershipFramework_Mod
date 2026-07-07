package com.eoframework.client;

import com.eoframework.common.EOFDebug;
import com.eoframework.network.StorageCommitC2SPayload;
import com.eoframework.network.StorageInsertSlotC2SPayload;
import com.eoframework.network.StorageSlotResponseC2SPayload;
import com.eoframework.network.StorageOwnerSessionC2SPayload;
import com.eoframework.network.StoragePlaceCarriedToInventoryC2SPayload;
import com.eoframework.network.StorageTakeSlotC2SPayload;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.eoframework.common.EOFDebug.Flag.*;

public class ClientLocalStorageScreen extends ContainerScreen {
    private final BlockPos storagePos;
    private final boolean ownerView;
    private long ignoreSnapshotsUntilGameTime = 0;
    private boolean carriedFromValidatedStorage = false;
    private long nextStorageRequestId = 1;
    private PendingOperation pendingOperation;
    private long currentCarriedToken = 0L;
    private int carriedSourceSlot = -1;
    private long ignoreClicksUntilGameTime = 0L;

    public enum PendingOpType {
        TAKE,
        QUICK_TAKE,
        INSERT,
        QUICK_INSERT,
        PLACE_CARRIED_TO_INVENTORY
    }

    private record PendingOperation(
            long requestId,
            PendingOpType type,
            int slotId,
            int sourceSlot,
            ItemStack stackSnapshot,
            long startedGameTime,
            long carriedToken
    ) {
        @Override
        public String toString() {
            return "requestId=" + requestId
                    + " type=" + type
                    + " slot=" + slotId
                    + " sourceSlot=" + sourceSlot
                    + " stack=" + stackSnapshot
                    + " startedGameTime=" + startedGameTime
                    + " carriedToken=" + carriedToken;
        }
    }

    public ClientLocalStorageScreen(ChestMenu menu, Inventory playerInventory, Component title, BlockPos storagePos, boolean ownerView) {
        super(menu, playerInventory, title);
        this.storagePos = storagePos.immutable();
        this.ownerView = ownerView;
        if (ownerView) {
            PacketDistributor.sendToServer(new StorageOwnerSessionC2SPayload(this.storagePos, true));
        }
    }

    @Override
    public void removed() {
        if (hasPendingOperation()) {
            clearPending(pendingOperation.requestId());
        }
        if (ownerView) {
            commitStorage();
            PacketDistributor.sendToServer(new StorageOwnerSessionC2SPayload(storagePos, false));
        }

        super.removed();
        ClientStorageAnimations.closeAll();
        ClientLocalStorageSession.endWithGrace(20);
    }

    private void commitStorage() {
        int storageSlots = this.menu.getRowCount() * 9;
        List<ItemStack> items = new ArrayList<>();

        for (int i = 0; i < storageSlots; i++) {
            items.add(this.menu.getSlot(i).getItem().copy());
        }

        PacketDistributor.sendToServer(new StorageCommitC2SPayload(storagePos, items));

        if (this.minecraft != null && this.minecraft.level != null) {
            ignoreSnapshotsUntilGameTime = this.minecraft.level.getGameTime() + 10;
        }
    }

    public boolean shouldBlockVanillaClick(int slotId, ClickType type) {
        if (ownerView) return false;

        int storageSlots = getStorageSlotCount();
        boolean menuSlot = slotId >= 0 && slotId < this.menu.slots.size();
        boolean storageSlot = slotId >= 0 && slotId < storageSlots;
        boolean inventorySlot = slotId >= storageSlots && slotId < this.menu.slots.size();

        boolean carriedValidated = carriedFromValidatedStorage && !this.menu.getCarried().isEmpty();
        boolean outsideSlot = slotId == -999 || slotId < 0 || slotId >= this.menu.slots.size();

        if (hasPendingOperation()) {
            return true;
        }

        if (isClickCooldownActive()) {
            return storageSlot || inventorySlot || outsideSlot || type == ClickType.QUICK_MOVE || type == ClickType.PICKUP_ALL;
        }

        return storageSlot
                || (type == ClickType.QUICK_MOVE && menuSlot)
                || (inventorySlot && carriedValidated)
                || (outsideSlot && carriedValidated);
    }

    public void handleNonOwnerValidatedClick(Slot slot, int slotId, int mouseButton, ClickType type) {
        logClickEntry(slot, slotId, mouseButton, type);
        handleNonOwnerStorageClick(slotId, type);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        int storageSlots = this.menu.getRowCount() * 9;

        if (ownerView) {
            ItemStack before = slotId >= 0 && slotId < storageSlots
                    ? this.menu.getSlot(slotId).getItem().copy()
                    : ItemStack.EMPTY;
            EOFDebug.log(STORAGE_CLICK, "owner click slotId={} current={}", slotId, before);
            super.slotClicked(slot, slotId, mouseButton, type);
            commitStorage();
            return;
        }

        if (type != ClickType.QUICK_MOVE && (slotId < 0 || slotId >= storageSlots)) {
            if (hasPendingOperation()) {
                logPendingBlock(slotId);
                return;
            }
            if (isClickCooldownActive()) {
                logCooldownBlock(slotId);
                return;
            }
            if (carriedFromValidatedStorage && !this.menu.getCarried().isEmpty()) {
                if (slotId >= storageSlots && slotId < this.menu.slots.size()) {
                    handlePlaceCarriedToInventory(slotId);
                    return;
                }
                logDecision("IGNORE", slotId, "validated_carried_invalid_or_outside_slot");
                return;
            }
            ItemStack beforeCarried = this.menu.getCarried().copy();
            ItemStack beforeSlot = slotId >= 0 && slotId < this.menu.slots.size() ? this.menu.getSlot(slotId).getItem().copy() : ItemStack.EMPTY;
            super.slotClicked(slot, slotId, mouseButton, type);
            ItemStack afterCarried = this.menu.getCarried().copy();
            if (afterCarried.isEmpty()) {
                carriedFromValidatedStorage = false;
                carriedSourceSlot = -1;
            } else if (beforeCarried.isEmpty() && slotId >= storageSlots && slotId < this.menu.slots.size()
                    && !beforeSlot.isEmpty() && ItemStack.isSameItemSameComponents(beforeSlot, afterCarried)) {
                carriedFromValidatedStorage = false;
                carriedSourceSlot = slotId;
                currentCarriedToken = 0L;
                EOFDebug.log(STORAGE_CURSOR, "inventory cursor source captured sourceSlot={} invIndex={} carried={}",
                        carriedSourceSlot, menuSlotIdToPlayerInventoryIndex(carriedSourceSlot, storageSlots), afterCarried);
            }
            return;
        }

        handleNonOwnerStorageClick(slotId, type);
    }

    private void logClickEntry(Slot slot, int slotId, int mouseButton, ClickType type) {
        boolean hasSlot = slotId >= 0 && slotId < this.menu.slots.size();
        boolean slotHasItem = hasSlot && this.menu.getSlot(slotId).hasItem();
        EOFDebug.log(STORAGE_CLICK, "entry ownerView={} slotId={} clickType={} mouseButton={} hasSlot={} slotHasItem={} carried={} pendingOperation={} carriedToken={} carriedFromStorageValidated={}",
                ownerView, slotId, type, mouseButton, hasSlot, slotHasItem, this.menu.getCarried(), pendingOperation, currentCarriedToken, carriedFromValidatedStorage);
    }

    private void logDecision(String action, int slotId, String reason) {
        EOFDebug.log(STORAGE_CLICK, "decision action={} slotId={} reason={} carried={} pendingOperation={} carriedToken={} carriedFromStorageValidated={}",
                action, slotId, reason, this.menu.getCarried(), pendingOperation, currentCarriedToken, carriedFromValidatedStorage);
    }

    private void setCarriedForStorage(ItemStack stack, String origin) {
        ItemStack before = this.menu.getCarried().copy();
        EOFDebug.log(STORAGE_CURSOR, "menu.setCarried BEFORE origin={} beforeCarried={} requestedCarried={} pendingOperation={} carriedToken={} carriedFromStorageValidated={}",
                origin, before, stack, pendingOperation, currentCarriedToken, carriedFromValidatedStorage);
        this.menu.setCarried(stack);
        EOFDebug.log(STORAGE_CURSOR, "menu.setCarried AFTER origin={} beforeCarried={} afterCarried={} pendingOperation={} carriedToken={} carriedFromStorageValidated={}",
                origin, before, this.menu.getCarried(), pendingOperation, currentCarriedToken, carriedFromValidatedStorage);
    }

    private long currentGameTime() {
        return this.minecraft != null && this.minecraft.level != null ? this.minecraft.level.getGameTime() : 0L;
    }

    private boolean isClickCooldownActive() {
        return currentGameTime() < ignoreClicksUntilGameTime;
    }

    private void startPostTakeCooldown() {
        ignoreClicksUntilGameTime = currentGameTime() + 3L;
        EOFDebug.log(STORAGE_PENDING, "post_take_cooldown untilTick={} currentTick={} carried={} token={} pending={}",
                ignoreClicksUntilGameTime, currentGameTime(), this.menu.getCarried(), currentCarriedToken, pendingOperation);
    }

    private void handleNonOwnerStorageClick(int slotId, ClickType type) {
        int storageSlots = getStorageSlotCount();
        ItemStack carriedBefore = this.menu.getCarried().copy();

        if (hasPendingOperation()) {
            logPendingBlock(slotId);
            return;
        }

        if (isClickCooldownActive()) {
            logCooldownBlock(slotId);
            return;
        }

        if (slotId >= 0 && slotId < storageSlots) {
            ItemStack slotStack = this.menu.getSlot(slotId).getItem().copy();

            if (type == ClickType.PICKUP_ALL) {
                logDecision("IGNORE", slotId, carriedFromValidatedStorage && !carriedBefore.isEmpty()
                        ? "pickup_all_validated_cursor_blocked"
                        : "pickup_all_non_owner_storage_blocked");
                return;
            }

            if (type != ClickType.PICKUP && type != ClickType.QUICK_MOVE) {
                logDecision("IGNORE", slotId, "unsupported_click_type_" + type);
                return;
            }

            if (type == ClickType.QUICK_MOVE) {
                if (slotStack.isEmpty()) {
                    logEmptySlotRequest(slotId);
                    return;
                }
                logDecision("TAKE", slotId, "quick_move_storage_to_inventory");
                long requestId = beginPending(PendingOpType.QUICK_TAKE, slotId, -1, slotStack);
                PacketDistributor.sendToServer(new StorageTakeSlotC2SPayload(storagePos, slotId, true, requestId));
                return;
            }

            if (!carriedBefore.isEmpty()) {
                if (!carriedFromValidatedStorage) {
                    if (carriedSourceSlot < storageSlots || carriedSourceSlot >= this.menu.slots.size()) {
                        logDecision("IGNORE", slotId, "NO_SOURCE_SLOT carried=" + carriedBefore);
                        logDenied("INSERT", -1L, slotId, carriedSourceSlot, storageSlots, menuSlotIdToPlayerInventoryIndex(carriedSourceSlot, storageSlots), "NO_SOURCE_SLOT");
                        return;
                    }
                    logDecision("INSERT", slotId, "inventory_cursor_to_storage");
                    long requestId = beginPending(PendingOpType.INSERT, slotId, carriedSourceSlot, carriedBefore);
                    EOFDebug.log(STORAGE_INSERT, "INSERT request token=0 pending={} requestId={} slot={} sourceSlot={} stack={}", pendingOperation, requestId, slotId, carriedSourceSlot, carriedBefore);
                    PacketDistributor.sendToServer(
                            new StorageInsertSlotC2SPayload(storagePos, slotId, carriedSourceSlot, storageSlots, carriedBefore.copy(), requestId, 0L)
                    );
                    return;
                }
                logDecision("INSERT", slotId, "validated_cursor_to_storage");
                long requestId = beginPending(PendingOpType.INSERT, slotId, -1, carriedBefore);
                EOFDebug.log(STORAGE_INSERT, "INSERT request token={} pending={} requestId={} slot={} stack={}", currentCarriedToken, pendingOperation, requestId, slotId, carriedBefore);
                PacketDistributor.sendToServer(
                        new StorageInsertSlotC2SPayload(storagePos, slotId, -1, storageSlots, carriedBefore.copy(), requestId, currentCarriedToken)
                );
                return;
            }

            if (slotStack.isEmpty()) {
                logEmptySlotRequest(slotId);
                return;
            }

            logDecision("TAKE", slotId, "storage_to_cursor");
            long requestId = beginPending(PendingOpType.TAKE, slotId, -1, slotStack);
            PacketDistributor.sendToServer(new StorageTakeSlotC2SPayload(storagePos, slotId, false, requestId));
            return;
        }

        if (type == ClickType.QUICK_MOVE && slotId >= storageSlots && slotId < this.menu.slots.size()) {
            ItemStack invStack = this.menu.getSlot(slotId).getItem().copy();
            if (invStack.isEmpty()) {
                logEmptySlotRequest(slotId);
                return;
            }

            int invIndex = menuSlotIdToPlayerInventoryIndex(slotId, storageSlots);
            if (invIndex < 0) {
                return;
            }

            logDecision("INSERT", slotId, "quick_move_inventory_to_storage");
            long requestId = beginPending(PendingOpType.QUICK_INSERT, -1, slotId, invStack);
            PacketDistributor.sendToServer(
                    new StorageInsertSlotC2SPayload(storagePos, -1, slotId, storageSlots, invStack.copy(), requestId, 0L)
            );
            return;
        }

        if (slotId >= storageSlots && slotId < this.menu.slots.size() && carriedFromValidatedStorage && !carriedBefore.isEmpty()) {
            handlePlaceCarriedToInventory(slotId);
            return;
        }

        if ((slotId == -999 || slotId < 0 || slotId >= this.menu.slots.size()) && carriedFromValidatedStorage && !carriedBefore.isEmpty()) {
            logDecision("IGNORE", slotId, "validated_carried_outside_slot_no_drop");
        }
    }

    private void handlePlaceCarriedToInventory(int slotId) {
        int storageSlots = getStorageSlotCount();
        ItemStack carried = this.menu.getCarried().copy();
        int invIndex = menuSlotIdToPlayerInventoryIndex(slotId, storageSlots);
        if (carried.isEmpty() || invIndex < 0) {
            logDenied("PLACE_CARRIED_TO_INVENTORY", -1L, slotId, -1, storageSlots, invIndex, "empty_carried_or_bad_inventory_slot");
            return;
        }
        ItemStack target = this.menu.getSlot(slotId).getItem();
        if (!target.isEmpty() && (!ItemStack.isSameItemSameComponents(target, carried) || target.getCount() >= target.getMaxStackSize())) {
            logDenied("PLACE_CARRIED_TO_INVENTORY", -1L, slotId, -1, storageSlots, invIndex, "incompatible_target");
            return;
        }
        logDecision("PLACE_TO_INVENTORY", slotId, "validated_cursor_to_inventory");
        long requestId = beginPending(PendingOpType.PLACE_CARRIED_TO_INVENTORY, slotId, -1, carried);
        PacketDistributor.sendToServer(new StoragePlaceCarriedToInventoryC2SPayload(storagePos, slotId, storageSlots, carried, requestId));
    }

    public boolean hasPendingOperation() {
        return pendingOperation != null;
    }

    private long beginPending(PendingOpType type, int slotId, int sourceSlot, ItemStack stack) {
        long requestId = nextStorageRequestId++;
        long gameTime = this.minecraft != null && this.minecraft.level != null ? this.minecraft.level.getGameTime() : 0L;
        pendingOperation = new PendingOperation(requestId, type, slotId, sourceSlot, stack.copy(), gameTime, currentCarriedToken);
        EOFDebug.log(STORAGE_PENDING, "begin requestId={} type={} slot={} sourceSlot={} token={} carried={}", requestId, type, slotId, sourceSlot, currentCarriedToken, this.menu.getCarried());
        return requestId;
    }

    private void clearPending(long requestId) {
        ItemStack beforeCarried = this.menu.getCarried().copy();
        EOFDebug.log(STORAGE_PENDING, "clear requestId={} pending={} beforeCarried={} token={}", requestId, pendingOperation, beforeCarried, currentCarriedToken);
        pendingOperation = null;
        EOFDebug.log(STORAGE_PENDING, "clear requestId={} afterCarried={} token={}", requestId, this.menu.getCarried(), currentCarriedToken);
    }

    private void logDenied(String operation, long requestId, int slotId, int sourceSlot, int storageSlots, int invIndex, String reason) {
        EOFDebug.log(STORAGE_RESULT, "denied operation={} requestId={} pendingOperation={} carried={} carriedFromStorageValidated={} carriedToken={} sourceSlot={} storageSlots={} invIndex={} slotId={} reason={}", operation, requestId, pendingOperation, this.menu.getCarried(), carriedFromValidatedStorage, currentCarriedToken, sourceSlot, storageSlots, invIndex, slotId, reason);
    }

    private void logPendingBlock(int slotId) {
        logDenied("BLOCKED_BY_PENDING", pendingOperation == null ? -1L : pendingOperation.requestId(), slotId, pendingOperation == null ? -1 : pendingOperation.sourceSlot(), getStorageSlotCount(), menuSlotIdToPlayerInventoryIndex(slotId, getStorageSlotCount()), "pending_operation");
        EOFDebug.log(STORAGE_PENDING, "click blocked because pending slotId={} pending={} carried={} token={} carriedFromStorageValidated={}", slotId, pendingOperation, this.menu.getCarried(), currentCarriedToken, carriedFromValidatedStorage);
    }

    private void logCooldownBlock(int slotId) {
        EOFDebug.log(STORAGE_PENDING, "click blocked because cooldown slotId={} currentTick={} untilTick={} carried={} token={} carriedFromStorageValidated={}",
                slotId, currentGameTime(), ignoreClicksUntilGameTime, this.menu.getCarried(), currentCarriedToken, carriedFromValidatedStorage);
        logDecision("IGNORE", slotId, "post_take_cooldown");
    }

    private void logEmptySlotRequest(int slotId) {
        logDecision("IGNORE", slotId, "empty_slot");
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

    public void logStorageGuard(String point, int slotId, ClickType clickType, ItemStack beforeCarried, boolean cancel) {
        ItemStack afterCarried = this.menu.getCarried();
        EOFDebug.log(STORAGE_GUARD, "point={} slotId={} clickType={} ownerView={} beforeCarried={} afterCarried={} cancel={}", point, slotId, clickType, ownerView, beforeCarried, afterCarried, cancel);
    }

    public boolean isForStorage(BlockPos pos) {
        return this.storagePos.equals(pos);
    }

    public void handleRemoteTakeRequest(UUID requester, int slotId, boolean quickMove, long requestId) {
        int storageSlots = this.menu.getRowCount() * 9;

        if (!ownerView || slotId < 0 || slotId >= storageSlots) {

            PacketDistributor.sendToServer(new StorageSlotResponseC2SPayload(
                    requester,
                    storagePos,
                    slotId,
                    false,
                    quickMove,
                    ItemStack.EMPTY,
                    requestId
            ));
            return;
        }

        ItemStack current = this.menu.getSlot(slotId).getItem();
        EOFDebug.log(STORAGE_TAKE, "owner current slot before approve slotId={} current={}", slotId, current);

        if (current.isEmpty()) {
            EOFDebug.log(STORAGE_RESULT, "owner take result slotId={} accepted=false", slotId);
            PacketDistributor.sendToServer(new StorageSlotResponseC2SPayload(
                    requester,
                    storagePos,
                    slotId,
                    false,
                    quickMove,
                    ItemStack.EMPTY,
                    requestId
            ));
            return;
        }

        ItemStack taken = current.copy();
        this.menu.getSlot(slotId).set(ItemStack.EMPTY);

        commitStorage();

        EOFDebug.log(STORAGE_RESULT, "owner take result slotId={} accepted=true taken={} requester={}", slotId, taken, requester);

        PacketDistributor.sendToServer(new StorageSlotResponseC2SPayload(
                requester,
                storagePos,
                slotId,
                true,
                quickMove,
                taken,
                requestId
        ));
    }

    public void tickPendingTimeout() {
        if (!hasPendingOperation() || this.minecraft == null || this.minecraft.level == null) return;
        long age = this.minecraft.level.getGameTime() - pendingOperation.startedGameTime();
        if (age > 60L) {
            logDenied(pendingOperation.type().name(), pendingOperation.requestId(), pendingOperation.slotId(), pendingOperation.sourceSlot(), getStorageSlotCount(), menuSlotIdToPlayerInventoryIndex(pendingOperation.slotId(), getStorageSlotCount()), "pending_timeout_" + age + "t");
            boolean wasValidated = carriedFromValidatedStorage;
            carriedFromValidatedStorage = !this.menu.getCarried().isEmpty() && carriedFromValidatedStorage;
            if (!wasValidated) {
                currentCarriedToken = 0L;
            }
            clearPending(pendingOperation.requestId());
            refreshFromCache();
        }
    }

    public void handleSnapshotReceived(int snapshotSlots) {
        ItemStack beforeCarried = this.menu.getCarried().copy();
        long beforeToken = currentCarriedToken;
        boolean beforeValidated = carriedFromValidatedStorage;
        PendingOperation beforePending = pendingOperation;
        if (hasPendingOperation() && snapshotSlots != getStorageSlotCount()) {
            logDenied(pendingOperation.type().name(), pendingOperation.requestId(), pendingOperation.slotId(), pendingOperation.sourceSlot(), getStorageSlotCount(), menuSlotIdToPlayerInventoryIndex(pendingOperation.slotId(), getStorageSlotCount()), "snapshot_slot_count_changed_to_" + snapshotSlots + "_pending_preserved");
        }
        refreshFromCache();
        EOFDebug.log(STORAGE_SNAPSHOT, "client snapshot beforeCarried={} beforeToken={} beforePending={} beforeValidated={} afterCarried={} afterToken={} afterPending={} afterValidated={}",
                beforeCarried, beforeToken, beforePending, beforeValidated, this.menu.getCarried(), currentCarriedToken, pendingOperation, carriedFromValidatedStorage);
    }

    public void refreshFromCache() {
        if (!ownerView && hasPendingOperation()) {
            return;
        }
        if (ownerView && this.minecraft != null && this.minecraft.level != null) {
            if (this.minecraft.level.getGameTime() <= ignoreSnapshotsUntilGameTime) {
                return;
            }
        }
        List<ItemStack> cached = ClientStorageCache.get(storagePos);
        int storageSlots = this.menu.getRowCount() * 9;

        for (int i = 0; i < Math.min(storageSlots, cached.size()); i++) {
            this.menu.getSlot(i).set(cached.get(i).copy());
        }

        for (int i = cached.size(); i < storageSlots; i++) {
            this.menu.getSlot(i).set(ItemStack.EMPTY);
        }
    }

    public boolean isOwnerView() {
        return ownerView;
    }

    public int getStorageSlotCount() {
        return this.menu.getRowCount() * 9;
    }

    public void handleValidatedTakeResult(boolean accepted, boolean quickMove, ItemStack stack, long requestId, long carriedToken) {
        ItemStack beforeCarried = this.menu.getCarried().copy();
        EOFDebug.log(STORAGE_RESULT, "take result BEFORE requestId={} accepted={} quick={} stack={} beforeCarried={} token={} pending={} carriedFromStorageValidated={}",
                requestId, accepted, quickMove, stack, beforeCarried, currentCarriedToken, pendingOperation, carriedFromValidatedStorage);

        long expected = pendingOperation == null ? -1L : pendingOperation.requestId();
        EOFDebug.log(STORAGE_RESULT, "result requestId={} expected={}", requestId, expected);
        if (pendingOperation == null || requestId != pendingOperation.requestId()) {
            EOFDebug.log(STORAGE_RESULT, "ignored stale TAKE requestId={} pendingRequestId={}", requestId, expected);
            return;
        }

        PendingOpType expectedType = quickMove ? PendingOpType.QUICK_TAKE : PendingOpType.TAKE;
        if (pendingOperation.type() != expectedType) {
            EOFDebug.log(STORAGE_RESULT, "ignored mismatched TAKE type={} expected={}", pendingOperation.type(), expectedType);
            return;
        }

        if (!accepted || stack.isEmpty()) {
            carriedFromValidatedStorage = false;
            currentCarriedToken = 0L;
            clearPending(requestId);
            refreshFromCache();
            EOFDebug.log(STORAGE_RESULT, "requester applied result accepted={} quick={} stack={} beforeCarried={} afterCarried={}", accepted, quickMove, stack, beforeCarried, this.menu.getCarried());
            return;
        }

        if (quickMove) {
            setCarriedForStorage(ItemStack.EMPTY, "quick_take_result_clear_empty_cursor");
            carriedFromValidatedStorage = false;
            currentCarriedToken = 0L;
            clearPending(requestId);
            refreshFromCache();
            EOFDebug.log(STORAGE_RESULT, "requester applied result accepted={} quick={} stack={} beforeCarried={} afterCarried={}", accepted, quickMove, stack, beforeCarried, this.menu.getCarried());
            return;
        }

        clearPending(requestId);
        setCarriedForStorage(stack.copy(), "take_result_accepted");
        carriedFromValidatedStorage = true;
        currentCarriedToken = carriedToken;
        carriedSourceSlot = -1;
        startPostTakeCooldown();
        EOFDebug.log(STORAGE_TAKE, "client received token={} requestId={} stack={}", carriedToken, requestId, stack);
        EOFDebug.log(STORAGE_RESULT, "requester applied result accepted={} quick={} stack={} beforeCarried={} afterCarried={} token={} pending={} carriedFromStorageValidated={}",
                accepted, quickMove, stack, beforeCarried, this.menu.getCarried(), currentCarriedToken, pendingOperation, carriedFromValidatedStorage);
    }

    public void handleValidatedInsertResult(boolean accepted, int insertedCount, int sourceSlot, long requestId, long carriedToken) {
        EOFDebug.log(STORAGE_RESULT, "insert result BEFORE accepted={} inserted={} sourceSlot={} requestId={} token={} beforeCarried={} currentToken={} pending={} carriedFromStorageValidated={}",
                accepted, insertedCount, sourceSlot, requestId, carriedToken, this.menu.getCarried(), currentCarriedToken, pendingOperation, carriedFromValidatedStorage);

        long expected = pendingOperation == null ? -1L : pendingOperation.requestId();
        EOFDebug.log(STORAGE_RESULT, "result requestId={} expected={}", requestId, expected);
        if (pendingOperation == null || requestId != pendingOperation.requestId()) {
            EOFDebug.log(STORAGE_RESULT, "ignored stale INSERT requestId={} pendingRequestId={}", requestId, expected);
            return;
        }

        if (pendingOperation.type() != PendingOpType.INSERT && pendingOperation.type() != PendingOpType.QUICK_INSERT) {
            EOFDebug.log(STORAGE_RESULT, "ignored mismatched INSERT type={}", pendingOperation.type());
            return;
        }

        if (accepted && sourceSlot < 0) {
            if (carriedToken == currentCarriedToken) {
                setCarriedForStorage(ItemStack.EMPTY, "insert_result_accepted_consumed_token");
                EOFDebug.log(STORAGE_CURSOR, "token consumed reason=INSERT_ACCEPTED token={} requestId={} carriedBefore={}", carriedToken, requestId, this.menu.getCarried());
                carriedFromValidatedStorage = false;
                currentCarriedToken = 0L;
            }
        }
        if (accepted && sourceSlot >= 0) {
            carriedFromValidatedStorage = false;
            currentCarriedToken = 0L;
            carriedSourceSlot = -1;
            setCarriedForStorage(ItemStack.EMPTY, "insert_result_accepted_consumed_inventory_cursor");
        }

        if (!accepted && sourceSlot < 0) {
            carriedFromValidatedStorage = !this.menu.getCarried().isEmpty();
        }

        clearPending(requestId);
        refreshFromCache();
        EOFDebug.log(STORAGE_RESULT, "insert result AFTER accepted={} requestId={} afterCarried={} token={} pending={} carriedFromStorageValidated={}", accepted, requestId, this.menu.getCarried(), currentCarriedToken, pendingOperation, carriedFromValidatedStorage);
    }

    public void handleValidatedPlaceCarriedToInventoryResult(boolean accepted, int placedCount, int targetSlot, long requestId) {
        long expected = pendingOperation == null ? -1L : pendingOperation.requestId();
        EOFDebug.log(STORAGE_RESULT, "place inventory result requestId={} expected={} accepted={} placed={} beforeCarried={}", requestId, expected, accepted, placedCount, this.menu.getCarried());
        if (pendingOperation == null || requestId != pendingOperation.requestId()) {
            EOFDebug.log(STORAGE_RESULT, "ignored stale PLACE_CARRIED_TO_INVENTORY requestId={} pendingRequestId={}", requestId, expected);
            return;
        }
        if (pendingOperation.type() != PendingOpType.PLACE_CARRIED_TO_INVENTORY) {
            EOFDebug.log(STORAGE_RESULT, "ignored mismatched PLACE_CARRIED_TO_INVENTORY type={}", pendingOperation.type());
            return;
        }
        if (accepted && placedCount > 0) {
            ItemStack carried = this.menu.getCarried().copy();
            carried.shrink(placedCount);
            setCarriedForStorage(carried.isEmpty() ? ItemStack.EMPTY : carried, "place_to_inventory_result_accepted");
            if (this.menu.getCarried().isEmpty()) {
                EOFDebug.log(STORAGE_CURSOR, "token consumed reason=PLACE_TO_INVENTORY_ACCEPTED requestId={} targetSlot={}", requestId, targetSlot);
                currentCarriedToken = 0L;
            }
            carriedFromValidatedStorage = !this.menu.getCarried().isEmpty();
        }
        if (!accepted) {
            carriedFromValidatedStorage = !this.menu.getCarried().isEmpty();
        }
        clearPending(requestId);
        refreshFromCache();
        EOFDebug.log(STORAGE_RESULT, "place inventory result AFTER accepted={} requestId={} afterCarried={} token={} pending={} carriedFromStorageValidated={}", accepted, requestId, this.menu.getCarried(), currentCarriedToken, pendingOperation, carriedFromValidatedStorage);
    }

    public boolean isForStorageLoose(BlockPos pos) {
        return this.storagePos.equals(pos) || ClientStorageCache.has(pos);
    }
}
