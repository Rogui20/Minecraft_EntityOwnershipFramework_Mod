package com.eoframework.mixin.client.core;

import com.eoframework.client.ClientLocalStorageScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenStorageClickMixin {
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void eof$interceptAbstractContainerStorageClick(
            Slot slot,
            int slotId,
            int mouseButton,
            ClickType type,
            CallbackInfo ci
    ) {
        Object self = this;
        if (!(self instanceof ClientLocalStorageScreen screen)) return;

        int effectiveSlotId = slot != null ? slot.index : slotId;
        boolean cancel = screen.shouldBlockVanillaClick(effectiveSlotId, type);
        ItemStack beforeCarried = screen.getMenu().getCarried().copy();

        if (!cancel) {
            screen.logStorageGuard("AbstractContainerScreen.slotClicked", effectiveSlotId, type, beforeCarried, false);
            return;
        }

        screen.handleNonOwnerValidatedClick(slot, effectiveSlotId, mouseButton, type);
        screen.logStorageGuard("AbstractContainerScreen.slotClicked", effectiveSlotId, type, beforeCarried, true);
        ci.cancel();
    }
}
