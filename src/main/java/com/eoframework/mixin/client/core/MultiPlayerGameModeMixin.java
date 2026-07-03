package com.eoframework.mixin.client.core;

import com.eoframework.client.ClientBlockBreakRuntime;
import com.eoframework.client.ClientOwnedBlockRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Shadow
    private float destroyProgress;

    @Shadow
    private float destroyTicks;

    @Shadow
    private int destroyDelay;

    @Shadow
    private boolean isDestroying;

    @Shadow
    public abstract boolean destroyBlock(BlockPos pos);

    @Unique
    private BlockPos eof$pendingOwnerBreakPos;

    @Unique
    private BlockState eof$pendingOwnerBreakState;

    @Unique
    private ItemStack eof$pendingOwnerBreakTool = ItemStack.EMPTY;

    @Inject(method = "performUseItemOn", at = @At("HEAD"), cancellable = true)
    private void eof$ownerPlacement(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockPos placePos = hitResult.getBlockPos().relative(hitResult.getDirection());

        if (!ClientOwnedBlockRuntime.isCellOwnedByMe(placePos)) {
            ItemStack stack = player.getItemInHand(hand);

            ClientOwnedBlockRuntime.requestPlace(placePos, hitResult.getDirection(), stack);

            cir.setReturnValue(InteractionResult.SUCCESS);
            cir.cancel();
        }
    }


    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void eof$startOwnerAuthoritativeBreak(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) {
            ClientOwnedBlockRuntime.logOwnershipDecision("startDestroyBlock", pos, false);
            return;
        }

        ClientBlockBreakRuntime.beginNonOwnerAssist(pos);
        ClientOwnedBlockRuntime.logOwnershipDecision("startDestroyBlock", pos, true);
        cir.setReturnValue(true);
        cir.cancel();
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void eof$continueOwnerAuthoritativeBreak(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) {
            ClientBlockBreakRuntime.continueNonOwnerAssist(pos);
            ClientOwnedBlockRuntime.logOwnershipDecision("continueDestroyBlock", pos, true);
            cir.setReturnValue(true);
            cir.cancel();
            return;
        }

        ClientOwnedBlockRuntime.logOwnershipDecision("continueDestroyBlock", pos, false);
        if (mc.level == null || mc.player == null) return;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;

        this.destroyProgress += ClientBlockBreakRuntime.assistedProgress(pos, state);
        if (this.destroyProgress >= 1.0F) {
            this.isDestroying = false;
            this.destroyBlock(pos);
            this.destroyProgress = 0.0F;
            this.destroyTicks = 0.0F;
            this.destroyDelay = 5;
            ClientBlockBreakRuntime.sendOwnerProgress(pos, -1);
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("RETURN"))
    private void eof$broadcastOwnerBreakProgress(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) return;
        int stage = this.destroyProgress <= 0.0F ? -1 : (int)(this.destroyProgress * 10.0F);
        if (stage > 9) stage = 9;
        ClientBlockBreakRuntime.sendOwnerProgress(pos, stage);
    }

    @Inject(method = "stopDestroyBlock", at = @At("HEAD"))
    private void eof$stopOwnerAuthoritativeBreak(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        ClientBlockBreakRuntime.endNonOwnerAssist();
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void eof$captureOrRedirectBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.gameMode == null) return;

        if (!ClientOwnedBlockRuntime.isCellOwnedByMe(pos)) {
            ClientOwnedBlockRuntime.logOwnershipDecision("destroyBlock", pos, true);
            ClientOwnedBlockRuntime.requestBreakAsNonOwner(pos);

            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        ClientOwnedBlockRuntime.logOwnershipDecision("destroyBlock", pos, false);

        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;

        eof$pendingOwnerBreakPos = pos.immutable();
        eof$pendingOwnerBreakState = state;
        eof$pendingOwnerBreakTool = mc.player.getMainHandItem().copy();
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void eof$spawnOwnerBreakDrops(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;
        if (eof$pendingOwnerBreakPos == null) return;
        if (!eof$pendingOwnerBreakPos.equals(pos)) return;
        if (eof$pendingOwnerBreakState == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null && mc.gameMode.getPlayerMode().isCreative()) {
            eof$clearPendingBreak();
            return;
        }
        ClientOwnedBlockRuntime.suppressBreakEcho(pos, 100);
        ClientOwnedBlockRuntime.spawnOwnerDrops(
                eof$pendingOwnerBreakPos,
                eof$pendingOwnerBreakState,
                eof$pendingOwnerBreakTool
        );

        ClientOwnedBlockRuntime.notifyOwnerBreakWithClientDrops(eof$pendingOwnerBreakPos);

        eof$clearPendingBreak();
    }

    @Unique
    private void eof$clearPendingBreak() {
        eof$pendingOwnerBreakPos = null;
        eof$pendingOwnerBreakState = null;
        eof$pendingOwnerBreakTool = ItemStack.EMPTY;
    }
}