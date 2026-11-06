package com.serenesailing.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.serenesailing.SereneSailing;
import com.serenesailing.hold.HoldBlocks;
import com.serenesailing.hold.HoldRegistry;
import com.serenesailing.hold.HoldScanner;
import com.serenesailing.hold.SableWaterOcclusion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3d;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForgeMod;

/**
 * {@code /serenesailing holddebug}: stand where the water feels wrong and run it. Prints what the hold registry,
 * Sable's occlusion container and the player's own fluid state say, so "invisible water that still slows me down"
 * can be pinned on one layer instead of guessed at.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT)
public final class HoldDebugCommand {

    private static final double SURFACE_Y = 62.8889;
    private static final double DRAW_RANGE = 64.0;
    private static final int MAX_MARKERS = 4000;
    private static boolean draw = false;

    private HoldDebugCommand() {
    }

    /**
     * {@code /serenesailing holddebug draw}: marks every column of every registered hold's sealed volume with a small
     * flat box at the vanilla water surface height. GREEN = the surface cell of that column is inside the volume (the
     * ocean is hidden there); RED = the column is sealed but its surface cell is not (a gap). Any visible water that is
     * NOT under a green marker is outside the sealed volume.
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (!draw || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || mc.level == null) {
            return;
        }
        final HoldRegistry registry = HoldRegistry.peek(mc.level);
        final PoseStack poseStack = event.getPoseStack();
        if (registry == null || poseStack == null) {
            return;
        }
        final Vec3 cam = event.getCamera().getPosition();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        final Vector3d tmp = new Vector3d();
        final int[] drawn = {0};
        final AABB marker = new AABB(-0.16, -0.02, -0.16, 0.16, 0.02, 0.16);

        registry.forEachActive((sub, env) -> {
            final Pose3dc pose = sub.logicalPose();
            final HoldScanner.Frame frame = pose == null ? null
                    : HoldBlocks.frameOf(pose, env.minX(), env.minY(), env.minZ(), tmp);
            if (frame == null) {
                return;
            }
            for (int lz = env.volumeMinZ(); lz <= env.volumeMaxZ(); lz++) {
                for (int lx = env.volumeMinX(); lx <= env.volumeMaxX(); lx++) {
                    if (env.columnLo(lx, lz) > env.columnHi(lx, lz) || drawn[0] >= MAX_MARKERS) {
                        continue;
                    }
                    final double localSurface = frame.localY(SURFACE_Y, lx + 0.5, lz + 0.5);
                    final boolean covered = env.contains(lx, (int) Math.floor(localSurface), lz);
                    final Vector3d world = pose.transformPosition(tmp.set(lx + 0.5, localSurface, lz + 0.5));
                    final double dx = world.x - cam.x, dy = world.y - cam.y, dz = world.z - cam.z;
                    if (dx * dx + dy * dy + dz * dz > DRAW_RANGE * DRAW_RANGE) {
                        continue;
                    }
                    poseStack.pushPose();
                    poseStack.translate(dx, dy, dz);
                    LevelRenderer.renderLineBox(poseStack, lines, marker,
                            covered ? 0.1f : 1.0f, covered ? 1.0f : 0.1f, 0.1f, 1.0f);
                    poseStack.popPose();
                    drawn[0]++;
                }
            }
        });
        RenderSystem.disableDepthTest();
        buffers.endBatch(RenderType.lines());
        RenderSystem.enableDepthTest();
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("serenesailing")
                .then(Commands.literal("holddebug")
                        .executes(ctx -> run(ctx.getSource()))
                        .then(Commands.literal("draw").executes(ctx -> {
                            draw = !draw;
                            say(ctx.getSource(), "hold coverage markers " + (draw
                                    ? "ON: green = ocean hidden at this column, red = sealed column with a gap"
                                    : "OFF"));
                            return 1;
                        }))));
    }

    private static int run(CommandSourceStack source) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || Minecraft.getInstance().level == null) {
            return 0;
        }
        final var level = Minecraft.getInstance().level;
        final Vec3 feet = player.position();
        final Vec3 eye = player.getEyePosition();
        say(source, "--- Serene Sailing hold debug ---");
        say(source, String.format("feet=(%.2f, %.2f, %.2f) eye.y=%.3f", feet.x, feet.y, feet.z, eye.y));
        for (final String line : HoldRegistry.describe(level, feet)) {
            say(source, line);
        }
        say(source, "mod registry:  feet=" + HoldRegistry.isOccluded(level, feet)
                + " eye=" + HoldRegistry.isOccluded(level, eye)
                + " entityDry=" + HoldRegistry.isEntityInDryHold(level, feet, player.getBoundingBox().getCenter()));
        say(source, "Sable occlusion: feet=" + SableWaterOcclusion.isOccluded(level, feet)
                + " eye=" + SableWaterOcclusion.isOccluded(level, eye)
                + " rendererEnabled=" + SableWaterOcclusion.clientRendererEnabled());
        say(source, "player fluid state: isInWater=" + player.isInWater()
                + " isUnderWater=" + player.isUnderWater()
                + " isInFluidType=" + player.isInFluidType()
                + " waterHeight=" + String.format("%.3f", player.getFluidTypeHeight(NeoForgeMod.WATER_TYPE.value()))
                + " swimming=" + player.isSwimming());
        return 1;
    }

    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}

