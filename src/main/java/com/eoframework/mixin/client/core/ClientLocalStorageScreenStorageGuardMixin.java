package com.eoframework.mixin.client.core;

import com.eoframework.client.ClientLocalStorageScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLocalStorageScreen.class)
public class ClientLocalStorageScreenStorageGuardMixin {
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void eof$interceptNonOwnerStorageClick(
            Slot slot,
            int slotId,
            int mouseButton,
            ClickType type,
            CallbackInfo ci
    ) {
        ClientLocalStorageScreen screen = (ClientLocalStorageScreen) (Object) this;
        if (screen.isOwnerView()) return;

        int effectiveSlotId = slot != null ? slot.index : slotId;
        if (screen.isHandlingStorageGuardClick()) {
            screen.logStorageGuardRecursionBlocked("ClientLocalStorageScreen.slotClicked", effectiveSlotId, type);
            ci.cancel();
            return;
        }

        boolean cancel = screen.shouldBlockVanillaClick(effectiveSlotId, type);
        ItemStack beforeCarried = screen.getMenu().getCarried().copy();

        if (!cancel) {
            screen.logStorageGuard("ClientLocalStorageScreen.slotClicked", effectiveSlotId, type, beforeCarried, false);
            return;
        }

        screen.handleNonOwnerValidatedClick(slot, effectiveSlotId, mouseButton, type);
        screen.logStorageGuard("ClientLocalStorageScreen.slotClicked", effectiveSlotId, type, beforeCarried, true);
        ci.cancel();
    }
}
