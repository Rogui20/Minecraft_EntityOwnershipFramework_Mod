package com.eoframework.client;

import com.eoframework.network.ChunkOwnerLeavingC2SPayload;
import com.eoframework.network.ChunkOwnershipClaimC2SPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

public class ClientChunkOwnershipRuntime {
    private static ChunkPos lastChunk;
    private static final Map<ChunkPos, Long> LAST_CLAIM_TICK = new HashMap<>();

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            lastChunk = null;
            LAST_CLAIM_TICK.clear();
            return;
        }

        ChunkPos current = mc.player.chunkPosition();
        if (lastChunk != null && !lastChunk.equals(current)) {
            PacketDistributor.sendToServer(new ChunkOwnerLeavingC2SPayload(lastChunk.x, lastChunk.z));
        }
        lastChunk = current;

        if (!ClientOwnedBlockRuntime.knowsChunkOwner(current.x, current.z)) {
            long now = mc.level.getGameTime();
            Long last = LAST_CLAIM_TICK.get(current);
            if (last == null || now - last >= 20) {
                LAST_CLAIM_TICK.put(current, now);
                PacketDistributor.sendToServer(new ChunkOwnershipClaimC2SPayload(current.x, current.z));
            }
        }
    }
}
