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
    private ItemStack pendingInsertStack = ItemStack.EMPTY;
    private int pendingInsertSourceSlot = -1;
    private long ignoreSnapshotsUntilGameTime = 0;
    private boolean carriedFromValidatedStorage = false;
    private long nextStorageRequestId = 1;
    private long pendingRequestId = -1;
    private String pendingOperation = "NONE";

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
        return (slotId >= 0 && slotId < storageSlots)
                || (type == ClickType.QUICK_MOVE && slotId >= 0);
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

        if (slotId >= 0 && slotId < storageSlots) {
            if (!carriedBefore.isEmpty()) {
                long requestId = beginPending("INSERT", slotId, -1, carriedBefore);
                PacketDistributor.sendToServer(
                        new StorageInsertSlotC2SPayload(storagePos, slotId, -1, storageSlots, carriedBefore.copy(), requestId)
                );
                refreshFromCache();
                System.out.println("[EOF StorageClient] requestId=" + requestId + " operation=INSERT slotId=" + slotId
                        + " sourceSlot=-1 storageSlots=" + storageSlots + " invIndex=-1 carriedBefore=" + carriedBefore
                        + " carriedAfter=" + this.menu.getCarried() + " insert=true");
                return;
            }

            long requestId = beginPending(type == ClickType.QUICK_MOVE ? "QUICK_TAKE" : "TAKE", slotId, -1, ItemStack.EMPTY);
            PacketDistributor.sendToServer(
                    new StorageTakeSlotC2SPayload(storagePos, slotId, type == ClickType.QUICK_MOVE, requestId)
            );
            refreshFromCache();
            System.out.println("[EOF StorageClient] requestId=" + requestId + " operation=" + pendingOperation + " slotId=" + slotId
                    + " sourceSlot=-1 storageSlots=" + storageSlots + " invIndex=-1 carriedBefore=" + carriedBefore
                    + " carriedAfter=" + this.menu.getCarried() + " quickMove=" + (type == ClickType.QUICK_MOVE));
            return;
        }

        if (type == ClickType.QUICK_MOVE && slotId >= storageSlots && slotId < this.menu.slots.size()) {
            int invIndex = menuSlotIdToPlayerInventoryIndex(slotId, storageSlots);
            ItemStack invStack = this.menu.getSlot(slotId).getItem().copy();

            if (!invStack.isEmpty() && invIndex >= 0) {
                long requestId = beginPending("QUICK_INSERT", -1, slotId, invStack);
                PacketDistributor.sendToServer(
                        new StorageInsertSlotC2SPayload(storagePos, -1, slotId, storageSlots, invStack.copy(), requestId)
                );
                System.out.println("[EOF StorageClient] requestId=" + requestId + " operation=QUICK_INSERT slotId=-1 sourceSlot="
                        + slotId + " storageSlots=" + storageSlots + " invIndex=" + invIndex + " stackBefore=" + invStack
                        + " carriedBefore=" + carriedBefore + " carriedAfter=" + this.menu.getCarried());
            }

            refreshFromCache();
            return;
        }

        refreshFromCache();
    }

    private long beginPending(String operation, int slotId, int sourceSlot, ItemStack stack) {
        long requestId = nextStorageRequestId++;
        pendingRequestId = requestId;
        pendingOperation = operation;
        pendingInsertStack = stack.copy();
        pendingInsertSourceSlot = sourceSlot;
        System.out.println("[EOF StorageClient] requestId=" + requestId + " operation=" + operation
                + " pending=true slotId=" + slotId + " sourceSlot=" + sourceSlot + " stack=" + stack);
        return requestId;
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

    public void handleRemoteTakeRequest(UUID requester, int slotId, boolean quickMove) {
        int storageSlots = this.menu.getRowCount() * 9;

        if (!ownerView || slotId < 0 || slotId >= storageSlots) {

            PacketDistributor.sendToServer(new StorageSlotResponseC2SPayload(
                    requester,
                    storagePos,
                    slotId,
                    false,
                    quickMove,
                    ItemStack.EMPTY
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
                    ItemStack.EMPTY
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
                taken
        ));
    }

    public void refreshFromCache() {
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

        if (requestId != pendingRequestId) {
            System.out.println("[EOF StorageResult] ignored stale TAKE requestId=" + requestId + " pendingRequestId=" + pendingRequestId);
            refreshFromCache();
            return;
        }

        if (!accepted || stack.isEmpty()) {
            carriedFromValidatedStorage = false;
            pendingRequestId = -1;
            pendingOperation = "NONE";
            refreshFromCache();
            return;
        }

        if (quickMove) {
            this.menu.setCarried(ItemStack.EMPTY);
            carriedFromValidatedStorage = false;
            pendingRequestId = -1;
            pendingOperation = "NONE";
            refreshFromCache();
            return;
        }

        this.menu.setCarried(stack.copy());
        carriedFromValidatedStorage = true;
        pendingRequestId = -1;
        pendingOperation = "NONE";
    }

    public void handleValidatedInsertResult(boolean accepted, int insertedCount, int sourceSlot, long requestId) {
        System.out.println("[EOF StorageResult] requester applying insert result accepted="
                + accepted
                + " inserted=" + insertedCount + " sourceSlot=" + sourceSlot + " requestId=" + requestId);

        if (requestId != pendingRequestId) {
            System.out.println("[EOF StorageResult] ignored stale INSERT requestId=" + requestId + " pendingRequestId=" + pendingRequestId);
            refreshFromCache();
            return;
        }

        if (accepted && sourceSlot < 0 && !this.menu.getCarried().isEmpty()) {
            ItemStack carried = this.menu.getCarried().copy();
            carried.shrink(insertedCount);
            this.menu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }

        pendingInsertStack = ItemStack.EMPTY;
        pendingInsertSourceSlot = -1;
        pendingRequestId = -1;
        pendingOperation = "NONE";
        if (accepted) {
            carriedFromValidatedStorage = false;
        }
        refreshFromCache();
    }

    public boolean isForStorageLoose(BlockPos pos) {
        return this.storagePos.equals(pos) || ClientStorageCache.has(pos);
    }
}