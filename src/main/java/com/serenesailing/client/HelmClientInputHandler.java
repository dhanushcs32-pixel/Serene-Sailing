package com.serenesailing.client;

import com.serenesailing.SereneSailing;
import com.serenesailing.block.HelmBlock;
import com.serenesailing.entity.HelmBlockEntity;
import com.serenesailing.network.ServerboundSteerPayload;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side mouse-drag steering handler for the Ship's Wheel (Helm).
 *
 * <p>When the player right-click-holds on a {@link HelmBlock}, raw mouse delta
 * is read from {@link net.minecraft.client.MouseHandler#getXVelocity()} and
 * directly drives the steering angle. The camera is dynamically anchored relative
 * to the ship's coordinate frame with zero snapping on release.</p>
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class HelmClientInputHandler {

    // ---- Client steering state ----------------------------------------------

    /** True while the player is holding RMB on a helm block. */
    private static boolean isSteeringHelm = false;

    /** Position of the helm block being steered. */
    private static BlockPos activeHelmPos = null;

    /** Accumulated rudder angle from mouse drag. */
    private static float accumulatedRudder = 0.0f;

    /** Locked camera orientation while steering to prevent jitter. */
    private static float lockedYaw = 0.0f;
    private static float lockedPitch = 0.0f;

    /** Last recorded ship reference yaw for relative frame tracking. */
    private static float lastShipYaw = 0.0f;

    /** Tick counter for network throttling (send every 2 ticks). */
    private static int sendCooldown = 0;

    /** Last rudder value actually sent to the server, for delta detection. */
    private static float lastSentRudder = 0.0f;

    // ---- Public accessors ---------------------------------------------------

    /** Returns true if the player is currently steering the helm. */
    public static boolean isSteering() {
        return isSteeringHelm;
    }

    /** Returns the accumulated rudder angle. */
    public static float getAccumulatedRudder() {
        return accumulatedRudder;
    }

    // ---- Mouse button handler -----------------------------------------------

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (event.getAction() == GLFW.GLFW_PRESS) {
            // Check if we're looking at a helm block
            if (mc.hitResult instanceof BlockHitResult blockHit
                    && blockHit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = blockHit.getBlockPos();
                if (mc.level.getBlockState(pos).getBlock() instanceof HelmBlock) {
                    isSteeringHelm = true;
                    activeHelmPos = pos;
                    sendCooldown = 0;
                    lastSentRudder = 0.0f;

                    // Initialize relative camera tracking
                    lockedPitch = mc.player.getXRot();
                    lastShipYaw = getShipOrHelmYaw();
                    lockedYaw = mc.player.getYRot();

                    // Initialize accumulated rudder from the block entity's current state
                    if (mc.level.getBlockEntity(pos) instanceof HelmBlockEntity helm) {
                        accumulatedRudder = helm.getTargetRudderAngle();
                    } else {
                        accumulatedRudder = 0.0f;
                    }

                    // Send initial "actively held" packet
                    PacketDistributor.sendToServer(
                            new ServerboundSteerPayload(activeHelmPos, accumulatedRudder, true));
                    lastSentRudder = accumulatedRudder;

                    // Cancel the event to prevent arm swing / block interaction
                    event.setCanceled(true);
                }
            }
        } else if (event.getAction() == GLFW.GLFW_RELEASE) {
            if (isSteeringHelm) {
                releaseSteering();
                event.setCanceled(true);
            }
        }
    }

    // ---- Mouse Turn Interception (NeoForge 1.21.1) ---------------------------

    /**
     * Intercepts player turn calculations in {@link net.minecraft.client.MouseHandler#turnPlayer(double)}.
     * We read the raw mouse delta to directly drive the helm wheel and set effective mouse sensitivity
     * to zero so vanilla never applies head rotation while steering.
     */
    @SubscribeEvent
    public static void onCalculatePlayerTurn(CalculatePlayerTurnEvent event) {
        if (!isSteeringHelm || activeHelmPos == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            releaseSteering();
            return;
        }

        double dx = mc.mouseHandler.getXVelocity();

        // 1. Feed mouse movement directly into steering accumulator (reduced sensitivity for heavy feel)
        float sensitivity = 0.085f;
        accumulatedRudder = Mth.clamp(accumulatedRudder + (float) (dx * sensitivity), -35.0f, 35.0f);

        // 2. Directly update client-side prediction on the targeted block entity for 60+ FPS responsiveness
        if (mc.level.getBlockEntity(activeHelmPos) instanceof HelmBlockEntity helm) {
            helm.setTargetRudderAngleClient(accumulatedRudder);
        }

        // 3. Throttle network packet to server (every 2 ticks or on significant change)
        sendSteerPacketIfNeeded();

        // 4. Set sensitivity to nullify vanilla turn calculation:
        // Vanilla computes: d0 = sensitivity * 0.6f + 0.2f
        // Setting sensitivity = -0.2f / 0.6f guarantees d0 = 0.0, completely zeroing camera delta.
        event.setMouseSensitivity((double) -0.2f / 0.6f);
    }

    /**
     * Viewport lock tracking the ship's relative reference frame:
     * When the ship turns by deltaYaw, the locked camera turns with it.
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (isSteeringHelm && activeHelmPos != null) {
            float currentShipYaw = getShipOrHelmYaw();
            float deltaShipYaw = Mth.wrapDegrees(currentShipYaw - lastShipYaw);
            lastShipYaw = currentShipYaw;
            lockedYaw += deltaShipYaw;

            event.setYaw(lockedYaw);
            event.setPitch(lockedPitch);
        }
    }

    // ---- Mouse scroll handler -----------------------------------------------

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        // Consume scroll events while steering to prevent hotbar switching
        if (isSteeringHelm) {
            event.setCanceled(true);
        }
    }

    // ---- Safety & Tick Hooks ------------------------------------------------

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        if (sendCooldown > 0) {
            sendCooldown--;
        }

        if (!isSteeringHelm || activeHelmPos == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            releaseSteering();
            return;
        }

        // Auto-release if player walks > 4 blocks away from activeHelmPos
        if (mc.player.distanceToSqr(activeHelmPos.getX() + 0.5, activeHelmPos.getY() + 0.5, activeHelmPos.getZ() + 0.5) > 16.0) {
            releaseSteering();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        releaseSteering();
    }

    private static void sendSteerPacketIfNeeded() {
        if (!isSteeringHelm || activeHelmPos == null) {
            return;
        }
        float delta = Math.abs(accumulatedRudder - lastSentRudder);
        if (sendCooldown <= 0 || delta > 1.0f) {
            PacketDistributor.sendToServer(
                    new ServerboundSteerPayload(activeHelmPos, accumulatedRudder, true));
            lastSentRudder = accumulatedRudder;
            sendCooldown = 2;
        }
    }

    /**
     * Releases steering and synchronizes the player's entity rotation with the current
     * camera orientation to eliminate abrupt snapping.
     */
    private static void releaseSteering() {
        if (isSteeringHelm && activeHelmPos != null) {
            PacketDistributor.sendToServer(
                    new ServerboundSteerPayload(activeHelmPos, 0.0f, false));
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && isSteeringHelm) {
            mc.player.setYRot(lockedYaw);
            mc.player.setYHeadRot(lockedYaw);
            mc.player.setXRot(lockedPitch);
        }

        isSteeringHelm = false;
        activeHelmPos = null;
        accumulatedRudder = 0.0f;
        lastSentRudder = 0.0f;
    }

    /**
     * Resolves the current orientation (yaw) of the ship/sub-level containing the helm,
     * or the vehicle the player is riding, or fallback to the helm block's facing direction.
     */
    private static float getShipOrHelmYaw() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle() != null) {
            return mc.player.getVehicle().getYRot();
        }

        if (mc.level != null && activeHelmPos != null) {
            try {
                SubLevelContainer container = SubLevelContainer.getContainer(mc.level);
                if (container instanceof ClientSubLevelContainer clientContainer) {
                    LevelPlot plot = clientContainer.getPlot(new ChunkPos(activeHelmPos));
                    if (plot != null && plot.getSubLevel() instanceof ClientSubLevel csl) {
                        return getSubLevelYaw(csl);
                    }
                    for (ClientSubLevel csl : clientContainer.getAllSubLevels()) {
                        LevelPlot subPlot = csl.getPlot();
                        if (subPlot != null && subPlot.contains(new ChunkPos(activeHelmPos))) {
                            return getSubLevelYaw(csl);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // Fallback: use block facing direction
            if (mc.level.getBlockState(activeHelmPos).hasProperty(HelmBlock.FACING)) {
                return mc.level.getBlockState(activeHelmPos).getValue(HelmBlock.FACING).toYRot();
            }
        }
        return 0.0f;
    }

    private static float getSubLevelYaw(ClientSubLevel csl) {
        Quaterniondc q = csl.renderPose().orientation();
        Vector3d fwd = new Vector3d(0.0, 0.0, -1.0).rotate(q);
        return (float) Math.toDegrees(Math.atan2(-fwd.x, fwd.z));
    }
}
