package com.serenesailing.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.serenesailing.SereneSailing;
import com.serenesailing.hold.HoldBlocks;
import com.serenesailing.hold.HoldRegistry;
import com.serenesailing.hold.HoldScanner;
import com.serenesailing.item.ShipwrightSpectaclesItem;
import com.serenesailing.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hands-free shipwright inspection overlay.
 *
 * <p>
 * Activated whenever the local player wears {@link ShipwrightSpectaclesItem} in
 * their HEAD armor slot
 * or holds it in either hand. Dynamically performs connected block flood-fills
 * on unassembled structures
 * and displays color-coded wireframe bounding boxes (helm, sails, rudder,
 * capstan, hull bounds, leaks)
 * as well as a nautical HUD readout.
 * </p>
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT)
public final class ShipwrightSpectaclesOverlay {

    public static final int MAX_INSPECT_BLOCKS = 1024;
    private static final int SEARCH_RADIUS_XZ = 32;
    private static final int SEARCH_RADIUS_Y = 24;

    private static BlockPos lastTargetPos = null;
    private static long lastScanTick = -1;
    private static InspectionResult cachedResult = null;

    private ShipwrightSpectaclesOverlay() {
    }

    public static final class InspectionResult {
        public final BlockPos origin;
        public final Set<BlockPos> connectedBlocks;
        public final List<BlockPos> helms = new ArrayList<>();
        public final List<BlockPos> sails = new ArrayList<>();
        public final List<BlockPos> rudders = new ArrayList<>();
        public final List<BlockPos> capstans = new ArrayList<>();
        public final List<BlockPos> instruments = new ArrayList<>();
        public final List<BlockPos> potentialLeaks = new ArrayList<>();
        public AABB boundingBox;

        public InspectionResult(BlockPos origin, Set<BlockPos> connectedBlocks, AABB boundingBox) {
            this.origin = origin;
            this.connectedBlocks = connectedBlocks;
            this.boundingBox = boundingBox;
        }
    }

    private static boolean isInspectableBlock(ClientLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
            return false;
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && !HoldBlocks.isHull(state)) {
            return false;
        }
        if (state.is(ModBlocks.HELM.get())
                || state.is(ModBlocks.CANVAS_SAIL.get())
                || state.is(ModBlocks.RUDDER.get())
                || state.is(ModBlocks.CAPSTAN.get())
                || state.is(ModBlocks.WIND_VANE.get())
                || state.is(ModBlocks.TAFFRAIL_LOG.get())) {
            return true;
        }
        if (HoldBlocks.isHull(state)) {
            return true;
        }
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.WOOL)
                || state.is(Blocks.IRON_BLOCK)
                || state.is(Blocks.COPPER_BLOCK)
                || state.is(Blocks.CHAIN)
                || state.is(Blocks.LANTERN)
                || state.is(Blocks.CHEST)
                || state.is(Blocks.BARREL);
    }

    private static InspectionResult inspect(ClientLevel level, BlockPos origin) {
        BlockState originState = level.getBlockState(origin);
        if (!isInspectableBlock(level, origin, originState)) {
            return null;
        }

        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);

        int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
        int maxX = origin.getX(), maxY = origin.getY(), maxZ = origin.getZ();

        InspectionResult res = new InspectionResult(origin, visited, null);

        while (!queue.isEmpty() && visited.size() < MAX_INSPECT_BLOCKS) {
            BlockPos current = queue.poll();
            BlockState st = level.getBlockState(current);

            minX = Math.min(minX, current.getX());
            minY = Math.min(minY, current.getY());
            minZ = Math.min(minZ, current.getZ());
            maxX = Math.max(maxX, current.getX());
            maxY = Math.max(maxY, current.getY());
            maxZ = Math.max(maxZ, current.getZ());

            if (st.is(ModBlocks.HELM.get()))
                res.helms.add(current);
            else if (st.is(ModBlocks.CANVAS_SAIL.get()))
                res.sails.add(current);
            else if (st.is(ModBlocks.RUDDER.get()))
                res.rudders.add(current);
            else if (st.is(ModBlocks.CAPSTAN.get()))
                res.capstans.add(current);
            else if (st.is(ModBlocks.WIND_VANE.get()) || st.is(ModBlocks.TAFFRAIL_LOG.get()))
                res.instruments.add(current);

            // Waterline leak detection (submerged blocks bordering exterior water)
            if (current.getY() <= 63) {
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (!visited.contains(neighbor) && level.getBlockState(neighbor).is(Blocks.WATER)) {
                        if (!HoldBlocks.isHull(st)) {
                            res.potentialLeaks.add(current);
                            break;
                        }
                    }
                }
            }

            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (Math.abs(next.getX() - origin.getX()) > SEARCH_RADIUS_XZ
                        || Math.abs(next.getZ() - origin.getZ()) > SEARCH_RADIUS_XZ
                        || Math.abs(next.getY() - origin.getY()) > SEARCH_RADIUS_Y) {
                    continue;
                }
                if (visited.add(next)) {
                    if (isInspectableBlock(level, next, level.getBlockState(next))) {
                        queue.add(next);
                    }
                }
            }
        }

        res.boundingBox = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        return res;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || !ShipwrightSpectaclesItem.isEquippedOrHeld(player)) {
            cachedResult = null;
            return;
        }

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        HitResult hit = mc.hitResult;

        BlockPos targetPos = null;
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            targetPos = bhr.getBlockPos();
        }

        long gameTime = level.getGameTime();
        if (targetPos != null) {
            if (cachedResult == null || !targetPos.equals(lastTargetPos) || (gameTime - lastScanTick) > 10L) {
                cachedResult = inspect(level, targetPos);
                lastTargetPos = targetPos;
                lastScanTick = gameTime;
            }
        } else if ((gameTime - lastScanTick) > 20L) {
            cachedResult = null;
        }

        PoseStack poseStack = event.getPoseStack();
        VertexConsumer lines = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        // 1. Highlight assembled vessels if looking at / near Sable sublevels
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container instanceof ClientSubLevelContainer clientContainer) {
            for (ClientSubLevel sub : clientContainer.getAllSubLevels()) {
                BoundingBox3dc bb = sub.boundingBox();
                if (bb != null) {
                    AABB box = new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
                    if (box.inflate(4.0).contains(player.position()) || (targetPos != null
                            && box.contains(targetPos.getX(), targetPos.getY(), targetPos.getZ()))) {
                        HoldScanner.Envelope env = HoldRegistry.activeEnvelope(sub);
                        float r = env != null ? 0.3f : 0.8f;
                        float g = env != null ? 0.9f : 0.6f;
                        float b = env != null ? 1.0f : 0.2f;
                        LevelRenderer.renderLineBox(poseStack, lines, box, r, g, b, 0.75f);
                    }
                }
            }
        }

        // 2. Highlight unassembled ship block structure
        if (cachedResult != null && !cachedResult.connectedBlocks.isEmpty()) {
            // Overall hull bounds: antique brass wireframe
            LevelRenderer.renderLineBox(poseStack, lines, cachedResult.boundingBox, 0.95f, 0.80f, 0.25f, 0.90f);

            // Targeted block: white outline
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(cachedResult.origin), 1.0f, 1.0f, 1.0f, 1.0f);

            // Helms: bright gold
            for (BlockPos pos : cachedResult.helms) {
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos), 1.0f, 0.85f, 0.1f, 1.0f);
            }
            // Sails: sky-blue / cyan
            for (BlockPos pos : cachedResult.sails) {
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos), 0.35f, 0.85f, 1.0f, 0.85f);
            }
            // Rudders: emerald green
            for (BlockPos pos : cachedResult.rudders) {
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos), 0.2f, 1.0f, 0.45f, 0.95f);
            }
            // Capstans: bronze / copper
            for (BlockPos pos : cachedResult.capstans) {
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos), 0.85f, 0.5f, 0.15f, 0.95f);
            }
            // Wind Vanes & Taffrail Logs: warm amber
            for (BlockPos pos : cachedResult.instruments) {
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos), 1.0f, 0.6f, 0.2f, 0.95f);
            }
            // Potential Leaks: warning red
            for (BlockPos pos : cachedResult.potentialLeaks) {
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos).inflate(0.02), 1.0f, 0.15f, 0.15f, 0.95f);
            }

            // Regular hull blocks: subtle nautical cyan for blocks within 12m of target
            double targetX = cachedResult.origin.getX();
            double targetY = cachedResult.origin.getY();
            double targetZ = cachedResult.origin.getZ();
            for (BlockPos pos : cachedResult.connectedBlocks) {
                double distSq = pos.distToCenterSqr(targetX, targetY, targetZ);
                if (distSq <= 144.0 && !cachedResult.helms.contains(pos) && !cachedResult.sails.contains(pos)
                        && !cachedResult.rudders.contains(pos) && !cachedResult.capstans.contains(pos)
                        && !cachedResult.instruments.contains(pos) && !pos.equals(cachedResult.origin)) {
                    LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos), 0.25f, 0.65f, 0.90f, 0.35f);
                }
            }
        }

        poseStack.popPose();
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !ShipwrightSpectaclesItem.isEquippedOrHeld(player)) {
            return;
        }

        if (cachedResult == null || cachedResult.connectedBlocks.isEmpty()) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;
        int screenWidth = graphics.guiWidth();

        int blockCount = cachedResult.connectedBlocks.size();
        int helms = cachedResult.helms.size();
        int sails = cachedResult.sails.size();
        int rudders = cachedResult.rudders.size();
        int capstans = cachedResult.capstans.size();
        int leaks = cachedResult.potentialLeaks.size();

        int dx = (int) Math.round(cachedResult.boundingBox.getXsize());
        int dy = (int) Math.round(cachedResult.boundingBox.getYsize());
        int dz = (int) Math.round(cachedResult.boundingBox.getZsize());

        String title = "§6⚓ Shipwright's Spectacles §7(" + blockCount + " Blocks, " + dx + "×" + dy + "×" + dz + ")";
        String comp = "§fHelm: " + (helms > 0 ? "§a" + helms : "§c0")
                + " §7| §fSails: " + (sails > 0 ? "§b" + sails : "§70")
                + " §7| §fRudder: " + (rudders > 0 ? "§a" + rudders : "§70")
                + " §7| §fAnchor: " + (capstans > 0 ? "§6" + capstans : "§70");

        String status;
        if (leaks > 0) {
            status = "§c⚠ " + leaks + " Leaks Detected Below Waterline!";
        } else if (helms == 0) {
            status = "§e⚠ Missing Helm (Ship's Wheel) station";
        } else if (sails == 0 && rudders == 0) {
            status = "§e⚠ Vessel has no sails or rudder configured";
        } else {
            status = "§a✔ Watertight & Ready for Assembling";
        }

        int maxTextWidth = Math.max(font.width(title), Math.max(font.width(comp), font.width(status)));
        int panelWidth = maxTextWidth + 16;
        int panelHeight = 44;
        int x = (screenWidth - panelWidth) / 2;
        int y = 10;

        // Semi-transparent antique navy-tinted background card
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xCC0D1B2A);
        graphics.fill(x, y, x + panelWidth, y + 1, 0xFFE0A93B);
        graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, 0xFFE0A93B);
        graphics.fill(x, y, x + 1, y + panelHeight, 0xFFE0A93B);
        graphics.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, 0xFFE0A93B);

        graphics.drawString(font, title, x + (panelWidth - font.width(title)) / 2, y + 4, 0xFFFFFF, true);
        graphics.drawString(font, comp, x + (panelWidth - font.width(comp)) / 2, y + 17, 0xFFFFFF, true);
        graphics.drawString(font, status, x + (panelWidth - font.width(status)) / 2, y + 30, 0xFFFFFF, true);
    }
}
