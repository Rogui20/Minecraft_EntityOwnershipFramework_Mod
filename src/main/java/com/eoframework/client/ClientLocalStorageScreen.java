package com.eoframework.client;

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

public class ClientLocalStorageScreen extends ContainerScreen {
    private final BlockPos storagePos;
    private final boolean ownerView;
    private long ignoreSnapshotsUntilGameTime = 0;
    private boolean carriedFromValidatedStorage = false;
    private long nextStorageRequestId = 1;
    private PendingOperation pendingOperation;
    private long currentCarriedToken = 0L;

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

        if (hasPendingOperation() && (menuSlot || type == ClickType.QUICK_MOVE)) {
            return true;
        }

        return storageSlot
                || (type == ClickType.QUICK_MOVE && menuSlot)
                || (inventorySlot && carriedFromValidatedStorage && !this.menu.getCarried().isEmpty());
    }

    public void handleNonOwnerValidatedClick(Slot slot, int slotId, int mouseButton, ClickType type) {
        handleNonOwnerStorageClick(slotId, type);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        int storageSlots = this.menu.getRowCount() * 9;

        if (ownerView) {
            ItemStack before = slotId >= 0 && slotId < storageSlots
                    ? this.menu.getSlot(slotId).getItem().copy()
                    : ItemStack.EMPTY;
            System.out.println("[EOF Storage] owner clicked same slot current slot=" + slotId + " current=" + before);
            super.slotClicked(slot, slotId, mouseButton, type);
            commitStorage();
            return;
        }

        if (type != ClickType.QUICK_MOVE && (slotId < 0 || slotId >= storageSlots)) {
            if (hasPendingOperation()) {
                logPendingBlock(slotId);
                return;
            }
            if (carriedFromValidatedStorage && !this.menu.getCarried().isEmpty() && slotId >= storageSlots) {
                handlePlaceCarriedToInventory(slotId);
                return;
            }
            super.slotClicked(slot, slotId, mouseButton, type);
            if (this.menu.getCarried().isEmpty()) {
                carriedFromValidatedStorage = false;
            }
            return;
        }

        handleNonOwnerStorageClick(slotId, type);
    }

    private void handleNonOwnerStorageClick(int slotId, ClickType type) {
        int storageSlots = getStorageSlotCount();
        ItemStack carriedBefore = this.menu.getCarried().copy();

        if (hasPendingOperation()) {
            logPendingBlock(slotId);
            return;
        }

        if (slotId >= 0 && slotId < storageSlots) {
            ItemStack slotStack = this.menu.getSlot(slotId).getItem().copy();

            if (type == ClickType.QUICK_MOVE) {
                if (slotStack.isEmpty()) {
                    logEmptySlotRequest(slotId);
                    return;
                }
                long requestId = beginPending(PendingOpType.QUICK_TAKE, slotId, -1, slotStack);
                PacketDistributor.sendToServer(new StorageTakeSlotC2SPayload(storagePos, slotId, true, requestId));
                return;
            }

            if (!carriedBefore.isEmpty()) {
                if (!carriedFromValidatedStorage) {
                    System.out.println("[EOF StoragePending] ignore unvalidated carried insert slot=" + slotId + " carried=" + carriedBefore);
                    return;
                }
                long requestId = beginPending(PendingOpType.INSERT, slotId, -1, carriedBefore);
                System.out.println("[EOF StorageInsert] INSERT request token=" + currentCarriedToken + " pending=" + pendingOperation + " requestId=" + requestId + " slot=" + slotId + " stack=" + carriedBefore);
                PacketDistributor.sendToServer(
                        new StorageInsertSlotC2SPayload(storagePos, slotId, -1, storageSlots, carriedBefore.copy(), requestId, currentCarriedToken)
                );
                return;
            }

            if (slotStack.isEmpty()) {
                logEmptySlotRequest(slotId);
                return;
            }

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

            long requestId = beginPending(PendingOpType.QUICK_INSERT, -1, slotId, invStack);
            PacketDistributor.sendToServer(
                    new StorageInsertSlotC2SPayload(storagePos, -1, slotId, storageSlots, invStack.copy(), requestId, 0L)
            );
            return;
        }

        if (slotId >= storageSlots && carriedFromValidatedStorage && !carriedBefore.isEmpty()) {
            handlePlaceCarriedToInventory(slotId);
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
        System.out.println("[EOF StoragePending] begin requestId=" + requestId + " type=" + type + " slot=" + slotId + " sourceSlot=" + sourceSlot + " token=" + currentCarriedToken);
        return requestId;
    }

    private void clearPending(long requestId) {
        ItemStack beforeCarried = this.menu.getCarried().copy();
        System.out.println("[EOF StoragePending] clear requestId=" + requestId + " pending=" + pendingOperation + " beforeCarried=" + beforeCarried + " token=" + currentCarriedToken);
        pendingOperation = null;
        System.out.println("[EOF StoragePending] clear requestId=" + requestId + " afterCarried=" + this.menu.getCarried());
    }

    private void logDenied(String operation, long requestId, int slotId, int sourceSlot, int storageSlots, int invIndex, String reason) {
        System.out.println("[EOF StorageDeny] operation=" + operation
                + " requestId=" + requestId
                + " pendingOperation=" + pendingOperation
                + " carried=" + this.menu.getCarried()
                + " carriedFromStorageValidated=" + carriedFromValidatedStorage
                + " carriedToken=" + currentCarriedToken
                + " sourceSlot=" + sourceSlot
                + " storageSlots=" + storageSlots
                + " invIndex=" + invIndex
                + " slotId=" + slotId
                + " reason=" + reason);
    }

    private void logPendingBlock(int slotId) {
        logDenied("BLOCKED_BY_PENDING", pendingOperation == null ? -1L : pendingOperation.requestId(), slotId, pendingOperation == null ? -1 : pendingOperation.sourceSlot(), getStorageSlotCount(), menuSlotIdToPlayerInventoryIndex(slotId, getStorageSlotCount()), "pending_operation");
        if (pendingOperation != null && pendingOperation.type() == PendingOpType.INSERT) {
            System.out.println("[EOF StorageInsert] blocked click because pending insert token=" + pendingOperation.carriedToken());
        }
    }

    private void logEmptySlotRequest(int slotId) {
        System.out.println("[EOF StoragePending] ignore empty slot request slot=" + slotId);
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
        System.out.println("[EOF StorageGuard] point="
                + point
                + " slotId="
                + slotId
                + " clickType="
                + clickType
                + " ownerView="
                + ownerView
                + " beforeCarried="
                + beforeCarried
                + " afterCarried="
                + afterCarried
                + " cancel="
                + cancel);
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
        System.out.println("[EOF Storage] owner current slot before approve=" + slotId + " current=" + current);

        if (current.isEmpty()) {
            System.out.println("[EOF Storage] owner approved/denied slot=" + slotId + " accepted=false");
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

        System.out.println("[EOF Storage] owner approved/denied slot=" + slotId + " accepted=true taken=" + taken + " requester=" + requester);

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
            carriedFromValidatedStorage = !this.menu.getCarried().isEmpty() && carriedFromValidatedStorage;
            clearPending(pendingOperation.requestId());
            refreshFromCache();
        }
    }

    public void handleSnapshotReceived(int snapshotSlots) {
        ItemStack beforeCarried = this.menu.getCarried().copy();
        if (hasPendingOperation() && snapshotSlots != getStorageSlotCount()) {
            logDenied(pendingOperation.type().name(), pendingOperation.requestId(), pendingOperation.slotId(), pendingOperation.sourceSlot(), getStorageSlotCount(), menuSlotIdToPlayerInventoryIndex(pendingOperation.slotId(), getStorageSlotCount()), "snapshot_slot_count_changed_to_" + snapshotSlots);
            clearPending(pendingOperation.requestId());
        }
        refreshFromCache();
        System.out.println("[EOF StorageSnapshot] client snapshot beforeCarried=" + beforeCarried + " beforeToken=" + currentCarriedToken + " afterCarried=" + this.menu.getCarried() + " afterToken=" + currentCarriedToken + " carriedFromStorageValidated=" + carriedFromValidatedStorage);
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
        System.out.println("[EOF Storage] requester received result accepted="
                + accepted
                + " quick=" + quickMove
                + " stack=" + stack
                + " beforeCarried=" + beforeCarried);

        long expected = pendingOperation == null ? -1L : pendingOperation.requestId();
        System.out.println("[EOF StoragePending] result requestId=" + requestId + " expected=" + expected);
        if (pendingOperation == null || requestId != pendingOperation.requestId()) {
            System.out.println("[EOF StorageResult] ignored stale TAKE requestId=" + requestId + " pendingRequestId=" + expected);
            return;
        }

        PendingOpType expectedType = quickMove ? PendingOpType.QUICK_TAKE : PendingOpType.TAKE;
        if (pendingOperation.type() != expectedType) {
            System.out.println("[EOF StorageResult] ignored mismatched TAKE type=" + pendingOperation.type() + " expected=" + expectedType);
            return;
        }

        if (!accepted || stack.isEmpty()) {
            carriedFromValidatedStorage = false;
            currentCarriedToken = 0L;
            clearPending(requestId);
            refreshFromCache();
            System.out.println("[EOF Storage] requester applied result accepted=" + accepted + " quick=" + quickMove + " stack=" + stack + " beforeCarried=" + beforeCarried + " afterCarried=" + this.menu.getCarried());
            return;
        }

        if (quickMove) {
            this.menu.setCarried(ItemStack.EMPTY);
            carriedFromValidatedStorage = false;
            currentCarriedToken = 0L;
            clearPending(requestId);
            refreshFromCache();
            System.out.println("[EOF Storage] requester applied result accepted=" + accepted + " quick=" + quickMove + " stack=" + stack + " beforeCarried=" + beforeCarried + " afterCarried=" + this.menu.getCarried());
            return;
        }

        this.menu.setCarried(stack.copy());
        carriedFromValidatedStorage = true;
        currentCarriedToken = carriedToken;
        System.out.println("[EOF StorageTake] client received token=" + carriedToken + " requestId=" + requestId + " stack=" + stack);
        clearPending(requestId);
        System.out.println("[EOF Storage] requester applied result accepted=" + accepted + " quick=" + quickMove + " stack=" + stack + " beforeCarried=" + beforeCarried + " afterCarried=" + this.menu.getCarried());
    }

    public void handleValidatedInsertResult(boolean accepted, int insertedCount, int sourceSlot, long requestId, long carriedToken) {
        System.out.println("[EOF StorageResult] requester applying insert result accepted="
                + accepted
                + " inserted=" + insertedCount + " sourceSlot=" + sourceSlot + " requestId=" + requestId + " token=" + carriedToken);

        long expected = pendingOperation == null ? -1L : pendingOperation.requestId();
        System.out.println("[EOF StoragePending] result requestId=" + requestId + " expected=" + expected);
        if (pendingOperation == null || requestId != pendingOperation.requestId()) {
            System.out.println("[EOF StorageResult] ignored stale INSERT requestId=" + requestId + " pendingRequestId=" + expected);
            return;
        }

        if (pendingOperation.type() != PendingOpType.INSERT && pendingOperation.type() != PendingOpType.QUICK_INSERT) {
            System.out.println("[EOF StorageResult] ignored mismatched INSERT type=" + pendingOperation.type());
            return;
        }

        if (accepted && sourceSlot < 0) {
            if (carriedToken == currentCarriedToken) {
                this.menu.setCarried(ItemStack.EMPTY);
                carriedFromValidatedStorage = false;
                currentCarriedToken = 0L;
            }
        }
        if (accepted && sourceSlot >= 0) {
            carriedFromValidatedStorage = false;
            currentCarriedToken = 0L;
        }

        if (!accepted && sourceSlot < 0) {
            carriedFromValidatedStorage = !this.menu.getCarried().isEmpty();
        }

        clearPending(requestId);
        refreshFromCache();
    }

    public void handleValidatedPlaceCarriedToInventoryResult(boolean accepted, int placedCount, int targetSlot, long requestId) {
        long expected = pendingOperation == null ? -1L : pendingOperation.requestId();
        System.out.println("[EOF StoragePending] place inventory result requestId=" + requestId + " expected=" + expected + " accepted=" + accepted + " placed=" + placedCount);
        if (pendingOperation == null || requestId != pendingOperation.requestId()) {
            System.out.println("[EOF StorageResult] ignored stale PLACE_CARRIED_TO_INVENTORY requestId=" + requestId + " pendingRequestId=" + expected);
            return;
        }
        if (pendingOperation.type() != PendingOpType.PLACE_CARRIED_TO_INVENTORY) {
            System.out.println("[EOF StorageResult] ignored mismatched PLACE_CARRIED_TO_INVENTORY type=" + pendingOperation.type());
            return;
        }
        if (accepted && placedCount > 0) {
            ItemStack carried = this.menu.getCarried().copy();
            carried.shrink(placedCount);
            this.menu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
            carriedFromValidatedStorage = !this.menu.getCarried().isEmpty();
        }
        if (!accepted) {
            carriedFromValidatedStorage = !this.menu.getCarried().isEmpty();
        }
        clearPending(requestId);
        refreshFromCache();
    }

    public boolean isForStorageLoose(BlockPos pos) {
        return this.storagePos.equals(pos) || ClientStorageCache.has(pos);
    }
}