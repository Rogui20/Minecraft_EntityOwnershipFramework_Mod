package com.eoframework.mixin.client.core;

import com.eoframework.client.ClientLocalStorageScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
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
        int effectiveSlotId = slot != null ? slot.index : slotId;
        boolean cancel = screen.shouldBlockVanillaClick(effectiveSlotId, type);

        System.out.println("[EOF StorageGuard] screen slotClicked intercepted slot="
                + effectiveSlotId
                + " type="
                + type
                + " ownerView="
                + screen.isOwnerView()
                + " cancel="
                + cancel);

        if (!cancel) return;

        screen.handleNonOwnerValidatedClick(slot, effectiveSlotId, mouseButton, type);
        ci.cancel();
    }
}
