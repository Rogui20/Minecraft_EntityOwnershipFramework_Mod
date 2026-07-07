package com.eoframework.network;

import com.eoframework.common.EOFDebug;
import com.eoframework.EOFramework;
import com.eoframework.client.ClientBlockLootProfiles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

public record BlockLootProfileS2CPayload(
        Map<String, StateLootProfile> profiles
) implements CustomPacketPayload {
    public record StateLootProfile(
            ResourceLocation blockId,
            String stateKey,
            Map<String, List<List<ItemStack>>> toolRolls
    ) {}

    public static final Type<BlockLootProfileS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_loot_profile_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockLootProfileS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BlockLootProfileS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    int profileCount = buf.readVarInt();
                    Map<String, StateLootProfile> profiles = new LinkedHashMap<>();

                    for (int i = 0; i < profileCount; i++) {
                        ResourceLocation blockId = buf.readResourceLocation();
                        String stateKey = buf.readUtf();

                        int toolCount = buf.readVarInt();
                        Map<String, List<List<ItemStack>>> toolRolls = new LinkedHashMap<>();

                        for (int t = 0; t < toolCount; t++) {
                            String toolProfile = buf.readUtf();
                            int rollCount = buf.readVarInt();

                            List<List<ItemStack>> rolls = new ArrayList<>();

                            for (int r = 0; r < rollCount; r++) {
                                int stackCount = buf.readVarInt();
                                List<ItemStack> roll = new ArrayList<>();

                                for (int s = 0; s < stackCount; s++) {
                                    roll.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                                }

                                rolls.add(roll);
                            }

                            toolRolls.put(toolProfile, rolls);
                        }

                        profiles.put(stateKey, new StateLootProfile(blockId, stateKey, toolRolls));
                    }

                    return new BlockLootProfileS2CPayload(profiles);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BlockLootProfileS2CPayload payload) {
                    buf.writeVarInt(payload.profiles().size());

                    for (StateLootProfile profile : payload.profiles().values()) {
                        buf.writeResourceLocation(profile.blockId());
                        buf.writeUtf(profile.stateKey());

                        buf.writeVarInt(profile.toolRolls().size());

                        for (var entry : profile.toolRolls().entrySet()) {
                            buf.writeUtf(entry.getKey());

                            List<List<ItemStack>> rolls = entry.getValue();
                            buf.writeVarInt(rolls.size());

                            for (List<ItemStack> roll : rolls) {
                                buf.writeVarInt(roll.size());

                                for (ItemStack stack : roll) {
                                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
                                }
                            }
                        }
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockLootProfileS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientBlockLootProfiles.clear();

            for (var entry : payload.profiles().entrySet()) {
                ClientBlockLootProfiles.put(entry.getKey(), entry.getValue());
            }

            EOFDebug.log(EOFDebug.Flag.NETWORK, "[EOF LootProfile] received {} block-state loot profiles", payload.profiles().size());
        });
    }
}
