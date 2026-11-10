package com.serenesailing.client;

import com.serenesailing.network.WaveStatePayload;
import com.serenesailing.physics.waves.GerstnerWaveEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-side receiver for {@link WaveStatePayload}. Kept in its own class so the server never loads Minecraft client code. */
public final class ClientWaveStateHandler {

    private ClientWaveStateHandler() {}

    public static void handle(final WaveStatePayload p, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            // Integrated server (single-player / LAN host) shares this JVM's static wave state with the client:
            // it is already authoritative, and re-applying the snapshot would double-step it.
            if (mc.getSingleplayerServer() != null) {
                return;
            }
            // context.player() is a common-side type: no client-only class (ClientLevel) is ever named here, so
            // verifying this class on a dedicated server (it is referenced from ModNetworking) cannot fail.
            Level level = context.player().level();
            if (level == null || level.dimension() != Level.OVERWORLD) {
                return;
            }
            GerstnerWaveEngine.setAuthoritativeScales(p.calmScale(), p.stormScale());
            GerstnerWaveEngine.getDimensionState(level).applyServerSnapshot(
                    p.serverTick(),
                    p.speed(), p.dirX(), p.dirZ(), p.storm(),
                    p.tSpeed(), p.tDirX(), p.tDirZ(), p.tStorm());
        });
    }
}

