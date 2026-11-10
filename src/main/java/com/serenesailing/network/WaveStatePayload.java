package com.serenesailing.network;

import com.serenesailing.SereneSailing;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the authoritative smoothed wave state of the Overworld.
 *
 * <p>The wave field is a pure function of (smoothed storm intensity, smoothed wind direction, aggressiveness
 * scales, game time). A remote client cannot derive the first three itself (Weather2's wind is only cached
 * server-side, and the scales come from each player's own client config), which is why the rendered waves
 * and the physics the server runs used to disagree on multiplayer.
 *
 * <p>Sent every {@link com.serenesailing.event.WaveStateSync#SYNC_INTERVAL_TICKS} ticks. It carries both the
 * current smoothed values and the targets they are heading to, so the client can replay the exact same
 * smoothing recurrence for the ticks that elapse between snapshots.
 */
public record WaveStatePayload(long serverTick,
                               float speed, float dirX, float dirZ, float storm,
                               float tSpeed, float tDirX, float tDirZ, float tStorm,
                               float calmScale, float stormScale) implements CustomPacketPayload {

    public static final Type<WaveStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "wave_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WaveStatePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WaveStatePayload decode(RegistryFriendlyByteBuf buf) {
                    return new WaveStatePayload(buf.readVarLong(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, WaveStatePayload p) {
                    buf.writeVarLong(p.serverTick());
                    buf.writeFloat(p.speed());
                    buf.writeFloat(p.dirX());
                    buf.writeFloat(p.dirZ());
                    buf.writeFloat(p.storm());
                    buf.writeFloat(p.tSpeed());
                    buf.writeFloat(p.tDirX());
                    buf.writeFloat(p.tDirZ());
                    buf.writeFloat(p.tStorm());
                    buf.writeFloat(p.calmScale());
                    buf.writeFloat(p.stormScale());
                }
            };

    /** Defence in depth: never let NaN/Inf from the wire reach the wave maths. */
    public WaveStatePayload {
        speed = finite(speed, 1.0f);
        dirX = finite(dirX, 0.0f);
        dirZ = finite(dirZ, -1.0f);
        storm = Math.max(0.0f, Math.min(1.0f, finite(storm, 0.15f)));
        tSpeed = finite(tSpeed, 1.0f);
        tDirX = finite(tDirX, 0.0f);
        tDirZ = finite(tDirZ, -1.0f);
        tStorm = Math.max(0.0f, Math.min(1.0f, finite(tStorm, 0.15f)));
        calmScale = Math.max(0.0f, Math.min(3.0f, finite(calmScale, 1.0f)));
        stormScale = Math.max(0.0f, Math.min(3.0f, finite(stormScale, 1.0f)));
    }

    private static float finite(float v, float fallback) {
        return (Float.isNaN(v) || Float.isInfinite(v)) ? fallback : v;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

