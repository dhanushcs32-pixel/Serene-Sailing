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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client -> server payload: sets the helm's unified steering angle from the
 * Ship's Wheel slider GUI. The server re-clamps the angle to the physical
 * stop [-45, +45] and syncs the change to all tracking clients through
 * {@link HelmBlockEntity#setSteeringAngle(float)}.
 */
public record SetHelmAnglePayload(BlockPos pos, float angle) implements CustomPacketPayload {

    private static final Map<UUID, long[]> RATE_LIMIT_MAP = new ConcurrentHashMap<>();

    public static final Type<SetHelmAnglePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "set_helm_angle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetHelmAnglePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetHelmAnglePayload::pos,
                    ByteBufCodecs.FLOAT, SetHelmAnglePayload::angle,
                    SetHelmAnglePayload::new);

    /** Defence-in-depth clamp and NaN guard so a hacked client cannot crash physics or exceed the rudder stop. */
    public SetHelmAnglePayload {
        if (pos == null) {
            pos = BlockPos.ZERO;
        }
        if (Float.isNaN(angle) || Float.isInfinite(angle)) {
            angle = 0.0f;
        }
        angle = Mth.clamp(angle, -45.0f, 45.0f);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler: must run on the main thread. */
    public static void handle(final SetHelmAnglePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.pos() == null) {
                return;
            }
            if (context.player() instanceof ServerPlayer serverPlayer
                    && serverPlayer.level() instanceof ServerLevel serverLevel) {
                if (serverPlayer.isRemoved()) {
                    return;
                }

                // Rate limiting: allow at most 20 steer packets per player per game tick
                long currentTick = serverLevel.getGameTime();
                long[] burst = RATE_LIMIT_MAP.computeIfAbsent(serverPlayer.getUUID(), k -> new long[]{currentTick, 0});
                if (burst[0] == currentTick) {
                    if (++burst[1] > 20) {
                        return; // Drop excess packet burst
                    }
                } else {
                    burst[0] = currentTick;
                    burst[1] = 1;
                }
                // FIX (Audit 106): Evict stale rate-limit entries for disconnected
                // players to prevent unbounded memory growth on long-running servers.
                if (RATE_LIMIT_MAP.size() > 64) {
                    int checked = 0;
                    var iter = RATE_LIMIT_MAP.entrySet().iterator();
                    while (iter.hasNext() && checked < 32) {
                        var e = iter.next();
                        if (currentTick - e.getValue()[0] > 200) {
                            iter.remove();
                        }
                        checked++;
                    }
                }
                SereneSailing.LOGGER.debug("[HELM-NET] Received angle: {} for pos: {}", payload.angle(), payload.pos());

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
                            if (subPlot != null && subPlot.contains(new ChunkPos(payload.pos()))) {
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

                // Security Check 1: If no HelmBlockEntity exists at pos, drop packet immediately.
                // Do NOT fall back to setting steering on arbitrary or all sublevels.
                if (helm == null) {
                    return;
                }

                // Security Check 2: Player reach & interaction distance check (max 10 blocks)
                net.minecraft.world.phys.Vec3 helmWorldCenter;
                if (helmSubLevel != null) {
                    helmWorldCenter = helmSubLevel.logicalPose().transformPosition(
                            net.minecraft.world.phys.Vec3.atCenterOf(payload.pos()));
                } else {
                    helmWorldCenter = net.minecraft.world.phys.Vec3.atCenterOf(payload.pos());
                }

                if (serverPlayer.distanceToSqr(helmWorldCenter) > 100.0) {
                    return;
                }

                helm.setSteeringAngle(payload.angle());

                if (helmSubLevel != null) {
                    com.serenesailing.event.ServerPhysicsHandler.setHelmAngle(helmSubLevel.getUniqueId(), payload.angle());
                }
            }
        });
    }
}
