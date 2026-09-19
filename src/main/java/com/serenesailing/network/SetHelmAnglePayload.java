package com.serenesailing.network;

import com.serenesailing.SereneSailing;
import com.serenesailing.entity.HelmBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server payload: sets the helm's unified steering angle from the
 * Ship's Wheel slider GUI. The server re-clamps the angle to the physical
 * stop [-30, +30] and syncs the change to all tracking clients through
 * {@link HelmBlockEntity#setSteeringAngle(float)}.
 */
public record SetHelmAnglePayload(BlockPos pos, float angle) implements CustomPacketPayload {

    public static final Type<SetHelmAnglePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "set_helm_angle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetHelmAnglePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetHelmAnglePayload::pos,
                    ByteBufCodecs.FLOAT, SetHelmAnglePayload::angle,
                    SetHelmAnglePayload::new);

    /** Defence-in-depth clamp so a hacked client cannot exceed the rudder stop. */
    public SetHelmAnglePayload {
        angle = Mth.clamp(angle, -30.0f, 30.0f);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler: must run on the main thread. */
    public static void handle(final SetHelmAnglePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer
                    && serverPlayer.level() instanceof ServerLevel serverLevel
                    && serverLevel.getBlockEntity(payload.pos()) instanceof HelmBlockEntity helm) {
                // setSteeringAngle clamps, marks changed, and pushes the block
                // entity update packet to all tracking clients.
                helm.setSteeringAngle(payload.angle());
            }
        });
    }
}
