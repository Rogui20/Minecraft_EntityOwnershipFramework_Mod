package com.eoframework.client;

import com.eoframework.EOFramework;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(
        modid = EOFramework.MODID,
        value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.GAME
)
public class ClientTick {
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ClientAuthEntities.tickClientAuthPickups();
        ClientBlockBreakRuntime.tickOwnerAssists();
        if (mc.screen instanceof ClientLocalStorageScreen screen) {
            screen.tickPendingTimeout();
        }
        tickCounter++;
        if (tickCounter % 20 != 0) return;
    }
}