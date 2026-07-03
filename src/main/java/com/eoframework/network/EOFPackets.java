package com.eoframework.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class EOFPackets {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(
                ReservedEntityIdsS2CPayload.TYPE,
                ReservedEntityIdsS2CPayload.STREAM_CODEC,
                ReservedEntityIdsS2CPayload::handle
        );

        registrar.playToServer(
                OwnerSpawnItemC2SPayload.TYPE,
                OwnerSpawnItemC2SPayload.STREAM_CODEC,
                OwnerSpawnItemC2SPayload::handle
        );

        registrar.playToClient(
                BlockLootProfileS2CPayload.TYPE,
                BlockLootProfileS2CPayload.STREAM_CODEC,
                BlockLootProfileS2CPayload::handle
        );

        registrar.playToServer(
                OwnerSpawnMobC2SPayload.TYPE,
                OwnerSpawnMobC2SPayload.STREAM_CODEC,
                OwnerSpawnMobC2SPayload::handle
        );

        registrar.playToClient(
                StorageSnapshotS2CPayload.TYPE,
                StorageSnapshotS2CPayload.STREAM_CODEC,
                StorageSnapshotS2CPayload::handle
        );


        registrar.playToServer(
                StorageCommitC2SPayload.TYPE,
                StorageCommitC2SPayload.STREAM_CODEC,
                StorageCommitC2SPayload::handle
        );

        registrar.playToServer(
                StorageTakeSlotC2SPayload.TYPE,
                StorageTakeSlotC2SPayload.STREAM_CODEC,
                StorageTakeSlotC2SPayload::handle
        );

        registrar.playToClient(
                StorageSlotRequestS2CPayload.TYPE,
                StorageSlotRequestS2CPayload.STREAM_CODEC,
                StorageSlotRequestS2CPayload::handle
        );

        registrar.playToServer(
                StorageSlotResponseC2SPayload.TYPE,
                StorageSlotResponseC2SPayload.STREAM_CODEC,
                StorageSlotResponseC2SPayload::handle
        );

        registrar.playToClient(
                StorageSlotResultS2CPayload.TYPE,
                StorageSlotResultS2CPayload.STREAM_CODEC,
                StorageSlotResultS2CPayload::handle
        );

        registrar.playToServer(
                StorageInsertSlotC2SPayload.TYPE,
                StorageInsertSlotC2SPayload.STREAM_CODEC,
                StorageInsertSlotC2SPayload::handle
        );

        registrar.playToClient(
                StorageInsertResultS2CPayload.TYPE,
                StorageInsertResultS2CPayload.STREAM_CODEC,
                StorageInsertResultS2CPayload::handle
        );

        registrar.playToServer(
                BlockBreakRequestC2SPayload.TYPE,
                BlockBreakRequestC2SPayload.STREAM_CODEC,
                BlockBreakRequestC2SPayload::handle
        );

        registrar.playToClient(
                BlockBreakOwnerRequestS2CPayload.TYPE,
                BlockBreakOwnerRequestS2CPayload.STREAM_CODEC,
                BlockBreakOwnerRequestS2CPayload::handle
        );


        registrar.playToServer(
                BlockBreakAssistC2SPayload.TYPE,
                BlockBreakAssistC2SPayload.STREAM_CODEC,
                BlockBreakAssistC2SPayload::handle
        );

        registrar.playToClient(
                BlockBreakAssistS2CPayload.TYPE,
                BlockBreakAssistS2CPayload.STREAM_CODEC,
                BlockBreakAssistS2CPayload::handle
        );

        registrar.playToServer(
                BlockBreakProgressC2SPayload.TYPE,
                BlockBreakProgressC2SPayload.STREAM_CODEC,
                BlockBreakProgressC2SPayload::handle
        );

        registrar.playToClient(
                BlockBreakProgressS2CPayload.TYPE,
                BlockBreakProgressS2CPayload.STREAM_CODEC,
                BlockBreakProgressS2CPayload::handle
        );

        registrar.playToServer(
                BlockPlaceRequestC2SPayload.TYPE,
                BlockPlaceRequestC2SPayload.STREAM_CODEC,
                BlockPlaceRequestC2SPayload::handle
        );

        registrar.playToServer(
                OwnerPickupItemC2SPayload.TYPE,
                OwnerPickupItemC2SPayload.STREAM_CODEC,
                OwnerPickupItemC2SPayload::handle
        );

    }
}