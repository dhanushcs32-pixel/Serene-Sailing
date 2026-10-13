package com.serenesailing.network;

import com.serenesailing.SereneSailing;
import com.serenesailing.entity.HelmBlockEntity;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server payload: sets the helm's rudder angle and active-hold
 * state from the mouse-drag steering handler. The server clamps the angle
 * to the physical stop [−35, +35] and updates the {@link HelmBlockEntity},
 * which broadcasts the change to all tracking clients via its update packet.
 */
public record ServerboundSteerPayload(BlockPos pos, float rudderAngle, boolean activelyHeld) implements CustomPacketPayload {

    public static final Type<ServerboundSteerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "serverbound_steer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundSteerPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ServerboundSteerPayload::pos,
                    ByteBufCodecs.FLOAT, ServerboundSteerPayload::rudderAngle,
                    ByteBufCodecs.BOOL, ServerboundSteerPayload::activelyHeld,
                    ServerboundSteerPayload::new);

    /** Defence-in-depth clamp so a hacked client cannot exceed the rudder stop. */
    public ServerboundSteerPayload {
        rudderAngle = Mth.clamp(rudderAngle, -HelmBlockEntity.MAX_RUDDER_ANGLE, HelmBlockEntity.MAX_RUDDER_ANGLE);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler: must run on the main thread. */
    public static void handle(final ServerboundSteerPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer
                    && serverPlayer.level() instanceof ServerLevel serverLevel) {
                SereneSailing.LOGGER.debug("[HELM-NET] Received rudder: {} held: {} for pos: {}",
                        payload.rudderAngle(), payload.activelyHeld(), payload.pos());

                HelmBlockEntity helm = null;
                ServerSubLevel helmSubLevel = null;

                BlockEntity directBe = serverLevel.getBlockEntity(payload.pos());
                if (directBe instanceof HelmBlockEntity h) {
                    helm = h;
                }

                SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
                if (container instanceof ServerSubLevelContainer serverContainer) {
                    LevelPlot plot = serverContainer.getPlot(new ChunkPos(payload.pos()));
                    if (plot != null) {
                        if (plot.getSubLevel() instanceof ServerSubLevel ssl) {
                            helmSubLevel = ssl;
                        }
                        if (helm == null) {
                            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                                LevelChunk chunk = holder.getChunk();
                                if (chunk != null) {
                                    BlockEntity chunkBe = chunk.getBlockEntity(payload.pos());
                                    if (chunkBe instanceof HelmBlockEntity h) {
                                        helm = h;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (helm == null || helmSubLevel == null) {
                        for (ServerSubLevel ssl : serverContainer.getAllSubLevels()) {
                            LevelPlot subPlot = ssl.getPlot();
                            if (subPlot != null) {
                                for (PlotChunkHolder holder : subPlot.getLoadedChunks()) {
                                    LevelChunk chunk = holder.getChunk();
                                    if (chunk != null) {
                                        BlockEntity chunkBe = chunk.getBlockEntity(payload.pos());
                                        if (chunkBe instanceof HelmBlockEntity h) {
                                            helm = h;
                                            helmSubLevel = ssl;
                                            break;
                                        }
                                    }
                                }
                                if (helm != null) break;
                            }
                        }
                    }
                }

                if (helm != null) {
                    helm.setSteeringInput(payload.rudderAngle(), payload.activelyHeld());
                }

                if (helmSubLevel != null) {
                    com.serenesailing.event.ServerPhysicsHandler.setHelmAngle(
                            helmSubLevel.getUniqueId(), payload.rudderAngle());
                } else if (container instanceof ServerSubLevelContainer serverContainer) {
                    for (ServerSubLevel ssl : serverContainer.getAllSubLevels()) {
                        com.serenesailing.event.ServerPhysicsHandler.setHelmAngle(
                                ssl.getUniqueId(), payload.rudderAngle());
                    }
                }
            }
        });
    }
}
