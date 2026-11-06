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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hands-free shipwright inspection overlay.
 *
 * <p>Activated when the local player wears {@link ShipwrightSpectaclesItem} in their HEAD armor slot
 * or holds it in either hand.
 *
 * <p>Transitively links all Create Super Glue boxes across the entire vessel (bow to stern, keel to masthead),
 * counts all glued ship blocks, and highlights <b>ONLY disconnected / unglued blocks</b> in pulsing red/orange
 * wireframes while keeping the rest of the ship clean and readable.</p>
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT)
public final class ShipwrightSpectaclesOverlay {

    public static final int MAX_SHIP_BLOCKS = 32768;
    /** Most disconnected blocks that are tracked (counted) per inspection; keeps the BFS shell bounded. */
    public static final int MAX_DISCONNECTED_TRACKED = 20_000;
    /** Most disconnected blocks that are OUTLINED per frame (the ones nearest the player): one line box is 24 vertices. */
    public static final int MAX_DISCONNECTED_DRAWN = 1_500;
    private static final double GLUE_SEARCH_RADIUS = 96.0;

    private static BlockPos lastTargetPos = null;
    private static long lastScanTick = -1;
    private static InspectionResult cachedResult = null;

    private ShipwrightSpectaclesOverlay() {}

    public static final class InspectionResult {
        public final BlockPos origin;
        public final Set<BlockPos> gluedBlocks = new HashSet<>();
        public final List<AABB> linkedGlueBoxes = new ArrayList<>();
        public final Set<BlockPos> disconnectedBlocks = new HashSet<>();
        /** The nearest {@link #MAX_DISCONNECTED_DRAWN} disconnected blocks, nearest first: what actually gets drawn. */
        public List<BlockPos> drawnDisconnected = List.of();
        public AABB gluedBounds = null;

        public int helms = 0;
        public int sails = 0;
        public int rudders = 0;
        public int capstans = 0;
        public int instruments = 0;

        public InspectionResult(BlockPos origin) {
            this.origin = origin;
        }
    }

    public static boolean isSuperGlueEntity(Entity entity) {
        if (entity == null) return false;
        String className = entity.getClass().getName();
        if (className.contains("SuperGlue")) return true;
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null && key.getPath().contains("super_glue");
    }

    private static boolean isInspectableBlock(ClientLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
            return false;
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && !HoldBlocks.isHull(state)) {
            return false;
        }
        // Exclude natural seabed terrain
        if (state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT)) {
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
                || state.is(BlockTags.BEDS)
                || state.is(Blocks.IRON_BLOCK)
                || state.is(Blocks.COPPER_BLOCK)
                || state.is(Blocks.CHAIN)
                || state.is(Blocks.LANTERN)
                || state.is(Blocks.SOUL_LANTERN)
                || state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.BARREL)
                || state.is(Blocks.GLASS)
                || state.is(Blocks.GLASS_PANE)
                || state.is(BlockTags.IMPERMEABLE);
    }

    private static InspectionResult inspect(ClientLevel level, BlockPos origin) {
        BlockState originState = level.getBlockState(origin);
        if (!isInspectableBlock(level, origin, originState)) {
            return null;
        }

        InspectionResult res = new InspectionResult(origin);

        // 1. Gather all SuperGlue entities in the search volume
        AABB searchArea = new AABB(origin).inflate(GLUE_SEARCH_RADIUS);
        List<Entity> allGlueEntities = level.getEntities((Entity) null, searchArea, ShipwrightSpectaclesOverlay::isSuperGlueEntity);

        // 2. Identify the seed glue entity containing or directly adjacent to origin
        List<Entity> seedGlue = new ArrayList<>();
        Vec3 originCenter = Vec3.atCenterOf(origin);
        for (Entity glue : allGlueEntities) {
            if (glue.getBoundingBox().inflate(0.5).contains(originCenter)) {
                seedGlue.add(glue);
            }
        }
        if (seedGlue.isEmpty()) {
            for (Entity glue : allGlueEntities) {
                if (glue.getBoundingBox().inflate(1.5).contains(originCenter)) {
                    seedGlue.add(glue);
                }
            }
        }

        // 3. Transitive BFS over all intersecting / touching SuperGlue boxes
        Set<Entity> linkedGlue = new HashSet<>(seedGlue);
        Deque<Entity> glueQueue = new ArrayDeque<>(seedGlue);

        while (!glueQueue.isEmpty()) {
            Entity current = glueQueue.poll();
            AABB boxA = current.getBoundingBox().inflate(0.25);

            for (Entity other : allGlueEntities) {
                if (linkedGlue.contains(other)) continue;
                AABB boxB = other.getBoundingBox().inflate(0.25);
                if (boxA.intersects(boxB)) {
                    linkedGlue.add(other);
                    glueQueue.add(other);
                }
            }
        }

        int shipMinY = Integer.MAX_VALUE;

        // 4. Collect all glued ship blocks inside the linked glue boxes
        if (!linkedGlue.isEmpty()) {
            glueBoxes:
            for (Entity glue : linkedGlue) {
                AABB bb = glue.getBoundingBox();
                res.linkedGlueBoxes.add(bb);

                int minX = (int) Math.floor(bb.minX);
                int maxX = (int) Math.ceil(bb.maxX);
                int minY = (int) Math.floor(bb.minY);
                int maxY = (int) Math.ceil(bb.maxY);
                int minZ = (int) Math.floor(bb.minZ);
                int maxZ = (int) Math.ceil(bb.maxZ);

                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        for (int x = minX; x < maxX; x++) {
                            if (res.gluedBlocks.size() >= MAX_SHIP_BLOCKS) break glueBoxes; // was: only left the innermost loop
                            BlockPos p = new BlockPos(x, y, z);
                            BlockState st = level.getBlockState(p);
                            if (isInspectableBlock(level, p, st)) {
                                res.gluedBlocks.add(p);
                                if (p.getY() < shipMinY) {
                                    shipMinY = p.getY();
                                }
                            }
                        }
                    }
                }
            }

            // Also attach self-connecting canvas sail sheets that touch glued blocks
            List<BlockPos> sailSeeds = new ArrayList<>();
            for (BlockPos p : res.gluedBlocks) {
                if (level.getBlockState(p).is(ModBlocks.CANVAS_SAIL.get())) {
                    sailSeeds.add(p);
                } else {
                    for (Direction dir : Direction.values()) {
                        BlockPos np = p.relative(dir);
                        if (!res.gluedBlocks.contains(np) && level.getBlockState(np).is(ModBlocks.CANVAS_SAIL.get())) {
                            sailSeeds.add(np);
                        }
                    }
                }
            }
            for (BlockPos seed : sailSeeds) {
                List<BlockPos> sheet = com.serenesailing.sail.SailPropagationHelper.findConnectedSailPositions(
                        seed, 1024, pos -> level.getBlockState(pos).is(ModBlocks.CANVAS_SAIL.get())
                );
                for (BlockPos sp : sheet) {
                    if (res.gluedBlocks.size() < MAX_SHIP_BLOCKS) {
                        res.gluedBlocks.add(sp);
                        if (sp.getY() < shipMinY) {
                            shipMinY = sp.getY();
                        }
                    }
                }
            }

            // Compute overall glued bounds
            int gMinX = Integer.MAX_VALUE, gMinY = Integer.MAX_VALUE, gMinZ = Integer.MAX_VALUE;
            int gMaxX = Integer.MIN_VALUE, gMaxY = Integer.MIN_VALUE, gMaxZ = Integer.MIN_VALUE;
            for (BlockPos p : res.gluedBlocks) {
                gMinX = Math.min(gMinX, p.getX());
                gMinY = Math.min(gMinY, p.getY());
                gMinZ = Math.min(gMinZ, p.getZ());
                gMaxX = Math.max(gMaxX, p.getX());
                gMaxY = Math.max(gMaxY, p.getY());
                gMaxZ = Math.max(gMaxZ, p.getZ());

                BlockState st = level.getBlockState(p);
                if (st.is(ModBlocks.HELM.get())) res.helms++;
                else if (st.is(ModBlocks.CANVAS_SAIL.get())) res.sails++;
                else if (st.is(ModBlocks.RUDDER.get())) res.rudders++;
                else if (st.is(ModBlocks.CAPSTAN.get())) res.capstans++;
                else if (st.is(ModBlocks.WIND_VANE.get()) || st.is(ModBlocks.TAFFRAIL_LOG.get())) res.instruments++;
            }
            if (!res.gluedBlocks.isEmpty()) {
                res.gluedBounds = new AABB(gMinX, gMinY, gMinZ, gMaxX + 1, gMaxY + 1, gMaxZ + 1);
            }

            // 5. Find ONLY disconnected / unglued blocks (within 4 blocks outward of gluedBlocks, above shipMinY)
            Deque<BlockPos> frontier = new ArrayDeque<>();
            Map<BlockPos, Integer> distanceMap = new HashMap<>();

            for (BlockPos p : res.gluedBlocks) {
                for (Direction dir : Direction.values()) {
                    BlockPos np = p.relative(dir);
                    if (!res.gluedBlocks.contains(np) && np.getY() >= shipMinY
                            && res.disconnectedBlocks.size() < MAX_DISCONNECTED_TRACKED) {
                        BlockState st = level.getBlockState(np);
                        if (isInspectableBlock(level, np, st)) {
                            if (res.disconnectedBlocks.add(np)) {
                                frontier.add(np);
                                distanceMap.put(np, 1);
                            }
                        }
                    }
                }
            }

            while (!frontier.isEmpty()) {
                BlockPos curr = frontier.poll();
                int dist = distanceMap.getOrDefault(curr, 1);
                if (dist >= 4 || res.disconnectedBlocks.size() >= MAX_DISCONNECTED_TRACKED) continue;

                for (Direction dir : Direction.values()) {
                    BlockPos next = curr.relative(dir);
                    if (!res.gluedBlocks.contains(next) && !res.disconnectedBlocks.contains(next) && next.getY() >= shipMinY) {
                        BlockState st = level.getBlockState(next);
                        if (isInspectableBlock(level, next, st)) {
                            res.disconnectedBlocks.add(next);
                            distanceMap.put(next, dist + 1);
                            frontier.add(next);
                        }
                    }
                }
            }

            // Also check unlinked glue entities within 6 blocks of the ship
            if (res.gluedBounds != null) {
                AABB nearShip = res.gluedBounds.inflate(6.0);
                for (Entity otherGlue : allGlueEntities) {
                    if (linkedGlue.contains(otherGlue)) continue;
                    AABB otherBox = otherGlue.getBoundingBox();
                    if (nearShip.intersects(otherBox)) {
                        int minX = (int) Math.floor(otherBox.minX);
                        int maxX = (int) Math.ceil(otherBox.maxX);
                        int minY = (int) Math.floor(otherBox.minY);
                        int maxY = (int) Math.ceil(otherBox.maxY);
                        int minZ = (int) Math.floor(otherBox.minZ);
                        int maxZ = (int) Math.ceil(otherBox.maxZ);
                        for (int y = Math.max(minY, shipMinY); y < maxY; y++) {
                            for (int z = minZ; z < maxZ; z++) {
                                for (int x = minX; x < maxX; x++) {
                                    BlockPos p = new BlockPos(x, y, z);
                                    if (res.disconnectedBlocks.size() < MAX_DISCONNECTED_TRACKED
                                            && !res.gluedBlocks.contains(p) && isInspectableBlock(level, p, level.getBlockState(p))) {
                                        res.disconnectedBlocks.add(p);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // No glue present at all: scan connected structure from origin as unglued blocks
            Deque<BlockPos> q = new ArrayDeque<>();
            q.add(origin);
            res.disconnectedBlocks.add(origin);
            while (!q.isEmpty() && res.disconnectedBlocks.size() < 1024) {
                BlockPos curr = q.poll();
                for (Direction dir : Direction.values()) {
                    BlockPos next = curr.relative(dir);
                    if (res.disconnectedBlocks.add(next)) {
                        if (isInspectableBlock(level, next, level.getBlockState(next))) {
                            q.add(next);
                        } else {
                            res.disconnectedBlocks.remove(next);
                        }
                    }
                }
            }
        }

        // Draw only the nearest few: an unbounded shell is tens of thousands of line boxes every frame.
        if (!res.disconnectedBlocks.isEmpty()) {
            final Minecraft mc = Minecraft.getInstance();
            final Vec3 from = mc.player != null ? mc.player.position() : originCenter;
            final List<BlockPos> sorted = new ArrayList<>(res.disconnectedBlocks);
            sorted.sort((a, b) -> Double.compare(a.distToCenterSqr(from), b.distToCenterSqr(from)));
            res.drawnDisconnected = List.copyOf(sorted.size() > MAX_DISCONNECTED_DRAWN
                    ? sorted.subList(0, MAX_DISCONNECTED_DRAWN) : sorted);
        }

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
                    if (box.inflate(4.0).contains(player.position()) || (targetPos != null && box.contains(targetPos.getX(), targetPos.getY(), targetPos.getZ()))) {
                        HoldScanner.Envelope env = HoldRegistry.activeEnvelope(sub);
                        float r = env != null ? 0.3f : 0.8f;
                        float g = env != null ? 0.9f : 0.6f;
                        float b = env != null ? 1.0f : 0.2f;
                        LevelRenderer.renderLineBox(poseStack, lines, box, r, g, b, 0.75f);
                    }
                }
            }
        }

        // 2. Render Unassembled Ship Glue & ONLY Disconnected Blocks
        if (cachedResult != null) {
            // (A) Draw subtle faint green boxes around each linked SuperGlueEntity zone (DO NOT clutter individual blocks!)
            for (AABB glueBox : cachedResult.linkedGlueBoxes) {
                LevelRenderer.renderLineBox(poseStack, lines, glueBox.inflate(0.02), 0.20f, 0.95f, 0.35f, 0.25f);
            }

            // (B) Faint outer brass hull bounding box
            if (cachedResult.gluedBounds != null) {
                LevelRenderer.renderLineBox(poseStack, lines, cachedResult.gluedBounds, 0.90f, 0.75f, 0.20f, 0.35f);
            }

            // (C) Targeted block single white outline
            if (targetPos != null) {
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(targetPos).inflate(0.01), 1.0f, 1.0f, 1.0f, 0.75f);
            }

            // (D) ONLY Disconnected / Unglued blocks: High-visibility pulsing Red/Orange wireframe!
            float pulse = 0.65f + 0.35f * (float) Math.sin(gameTime * 0.35f);
            for (BlockPos pos : cachedResult.drawnDisconnected) {
                AABB redBox = new AABB(pos).inflate(0.005);
                LevelRenderer.renderLineBox(poseStack, lines, redBox, 1.0f, 0.25f, 0.15f, pulse);
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

        if (cachedResult == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;
        int screenWidth = graphics.guiWidth();

        int gluedCount = cachedResult.gluedBlocks.size();
        int glueZones = cachedResult.linkedGlueBoxes.size();
        int disconnCount = cachedResult.disconnectedBlocks.size();

        int helms = cachedResult.helms;
        int sails = cachedResult.sails;
        int rudders = cachedResult.rudders;
        int capstans = cachedResult.capstans;

        String title = "§6⚓ Shipwright's Spectacles §7(" + String.format("%,d", gluedCount) + " Glued Blocks | " + glueZones + " Linked Glue Zones)";
        String comp = "§fHelm: " + (helms > 0 ? "§a" + helms : "§c0")
                + " §7| §fSails: " + (sails > 0 ? "§b" + sails : "§70")
                + " §7| §fRudder: " + (rudders > 0 ? "§a" + rudders : "§70")
                + " §7| §fAnchor: " + (capstans > 0 ? "§6" + capstans : "§70");

        String status;
        if (disconnCount > 0) {
            final boolean capped = disconnCount >= MAX_DISCONNECTED_TRACKED;
            final int shown = cachedResult.drawnDisconnected.size();
            status = "§c⚠ " + disconnCount + (capped ? "+" : "") + " Disconnected / Unglued Blocks"
                    + (shown < disconnCount ? " (nearest " + shown + " highlighted)" : " Highlighted in Red!");
        } else if (gluedCount == 0) {
            status = "§e⚠ Looking at unglued blocks (Apply Create Super Glue)";
        } else if (helms == 0) {
            status = "§e⚠ Missing Helm (Ship's Wheel) station";
        } else if (sails == 0 && rudders == 0) {
            status = "§e⚠ Vessel has no sails or rudder attached";
        } else {
            status = "§a✔ All Ship Blocks Glued & Connected (0 Disconnected)";
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
