package com.eoframework.client;

import com.eoframework.network.StorageCommitC2SPayload;
import com.eoframework.network.StorageInsertSlotC2SPayload;
import com.eoframework.network.StorageSlotResponseC2SPayload;
import com.eoframework.network.StorageOwnerSessionC2SPayload;
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

    public enum PendingOpType {
        TAKE,
        QUICK_TAKE,
        INSERT,
        QUICK_INSERT
    }

    private record PendingOperation(
            long requestId,
            PendingOpType type,
            int slotId,
            int sourceSlot,
            ItemStack stackSnapshot
    ) {
        @Override
        public String toString() {
            return "requestId=" + requestId
                    + " type=" + type
                    + " slot=" + slotId
                    + " sourceSlot=" + sourceSlot
                    + " stack=" + stackSnapshot;
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
                System.out.println("[EOF StorageClick] blocked cursor->inventory for non-owner carried=" + this.menu.getCarried());
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
                PacketDistributor.sendToServer(
                        new StorageInsertSlotC2SPayload(storagePos, slotId, -1, storageSlots, carriedBefore.copy(), requestId)
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
                    new StorageInsertSlotC2SPayload(storagePos, -1, slotId, storageSlots, invStack.copy(), requestId)
            );
            return;
        }

        if (slotId >= storageSlots && carriedFromValidatedStorage && !carriedBefore.isEmpty()) {
            System.out.println("[EOF StorageClick] blocked cursor->inventory for non-owner carried=" + carriedBefore);
        }
    }

    public boolean hasPendingOperation() {
        return pendingOperation != null;
    }

    private long beginPending(PendingOpType type, int slotId, int sourceSlot, ItemStack stack) {
        long requestId = nextStorageRequestId++;
        pendingOperation = new PendingOperation(requestId, type, slotId, sourceSlot, stack.copy());
        System.out.println("[EOF StoragePending] begin requestId=" + requestId + " type=" + type + " slot=" + slotId + " sourceSlot=" + sourceSlot);
        return requestId;
    }

    private void clearPending(long requestId) {
        System.out.println("[EOF StoragePending] clear requestId=" + requestId);
        pendingOperation = null;
    }

    private void logPendingBlock(int slotId) {
        System.out.println("[EOF StoragePending] block click because pending=" + pendingOperation + " slot=" + slotId);
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

    public void handleValidatedTakeResult(boolean accepted, boolean quickMove, ItemStack stack, long requestId) {
        System.out.println("[EOF Storage] requester received result accepted="
                + accepted
                + " quick=" + quickMove
                + " stack=" + stack);

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
            clearPending(requestId);
            refreshFromCache();
            return;
        }

        if (quickMove) {
            this.menu.setCarried(ItemStack.EMPTY);
            carriedFromValidatedStorage = false;
            clearPending(requestId);
            refreshFromCache();
            return;
        }

        this.menu.setCarried(stack.copy());
        carriedFromValidatedStorage = true;
        clearPending(requestId);
        refreshFromCache();
    }

    public void handleValidatedInsertResult(boolean accepted, int insertedCount, int sourceSlot, long requestId) {
        System.out.println("[EOF StorageResult] requester applying insert result accepted="
                + accepted
                + " inserted=" + insertedCount + " sourceSlot=" + sourceSlot + " requestId=" + requestId);

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

        if (accepted && sourceSlot < 0 && !this.menu.getCarried().isEmpty()) {
            ItemStack carried = this.menu.getCarried().copy();
            carried.shrink(insertedCount);
            this.menu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }

        if (accepted && sourceSlot < 0) {
            carriedFromValidatedStorage = !this.menu.getCarried().isEmpty();
        }
        if (accepted && sourceSlot >= 0) {
            carriedFromValidatedStorage = false;
        }

        clearPending(requestId);
        refreshFromCache();
    }

    public boolean isForStorageLoose(BlockPos pos) {
        return this.storagePos.equals(pos) || ClientStorageCache.has(pos);
    }
}