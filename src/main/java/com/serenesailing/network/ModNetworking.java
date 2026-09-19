package com.serenesailing.network;

import com.serenesailing.SereneSailing;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Network bootstrap: registers all Serene Sailing payloads on the mod event
 * bus during NeoForge's payload-handlers registration phase.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetworking {

    /** Protocol version for the payload set - bump on wire-format changes. */
    private static final String PROTOCOL_VERSION = "1";

    private ModNetworking() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(SetHelmAnglePayload.TYPE, SetHelmAnglePayload.STREAM_CODEC,
                SetHelmAnglePayload::handle);
    }
}
