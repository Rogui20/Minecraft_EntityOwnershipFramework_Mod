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

        // Não-owner clicando no baú: bloqueia visual e pede validação.
        if (slotId >= 0 && slotId < storageSlots) {
            ItemStack carried = this.menu.getCarried();

            if (!carried.isEmpty()) {
                pendingInsertStack = carried.copy();

                PacketDistributor.sendToServer(
                        new StorageInsertSlotC2SPayload(storagePos, slotId, -1, storageSlots, carried.copy())
                );

                refreshFromCache();
                System.out.println("[EOF StorageClick] ownerView=false slot=" + slotId + " type=" + type + " action=CANCEL_LOCAL_SEND_REQUEST insert=true");
                return;
            }

            PacketDistributor.sendToServer(
                    new StorageTakeSlotC2SPayload(storagePos, slotId, type == ClickType.QUICK_MOVE)
            );

            this.menu.setCarried(ItemStack.EMPTY);
            refreshFromCache();
            System.out.println("[EOF StorageClick] ownerView=false slot=" + slotId + " type=" + type + " action=CANCEL_LOCAL_SEND_REQUEST quickMove=" + (type == ClickType.QUICK_MOVE));
            return;
        }

        // Não-owner mexendo no próprio inventário: permitido.
        if (type != ClickType.QUICK_MOVE) {
            super.slotClicked(slot, slotId, mouseButton, type);
            return;
        }

        // Não-owner shift-click no próprio inventário -> tentar inserir no primeiro slot compatível do baú.
        if (type == ClickType.QUICK_MOVE && slotId >= storageSlots) {
            ItemStack invStack = this.menu.getSlot(slotId).getItem();

            if (!invStack.isEmpty()) {
                pendingInsertStack = invStack.copy();
                pendingInsertSourceSlot = slotId;

                PacketDistributor.sendToServer(
                        new StorageInsertSlotC2SPayload(storagePos, -1, slotId, storageSlots, invStack.copy())
                );
                System.out.println("[EOF StorageClick] ownerView=false slot=" + slotId + " type=" + type + " action=CANCEL_LOCAL_SEND_REQUEST quickMoveInventoryToStorage=true");
            }

            refreshFromCache();
            return;
        }

        this.menu.setCarried(ItemStack.EMPTY);
        refreshFromCache();
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

    public void handleValidatedTakeResult(boolean accepted, boolean quickMove, ItemStack stack) {
        System.out.println("[EOF Storage] requester received result accepted="
                + accepted
                + " quick=" + quickMove
                + " stack=" + stack);

        if (!accepted || stack.isEmpty()) {
            this.menu.setCarried(ItemStack.EMPTY);
            refreshFromCache();
            return;
        }

        if (quickMove) {
            this.menu.setCarried(ItemStack.EMPTY);
            refreshFromCache();
            return;
        }

        this.menu.setCarried(stack.copy());
    }

    public void handleValidatedInsertResult(boolean accepted, int insertedCount) {
        System.out.println("[EOF StorageResult] requester applying insert result accepted="
                + accepted
                + " inserted=" + insertedCount);

        if (accepted && pendingInsertSourceSlot < 0 && !this.menu.getCarried().isEmpty()) {
            ItemStack carried = this.menu.getCarried().copy();
            carried.shrink(insertedCount);
            this.menu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }

        pendingInsertStack = ItemStack.EMPTY;
        pendingInsertSourceSlot = -1;
        refreshFromCache();
    }

    public boolean isForStorageLoose(BlockPos pos) {
        return this.storagePos.equals(pos) || ClientStorageCache.has(pos);
    }
}