package com.serenesailing.hold;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Per-level (so per-side) registry of scanned sunken holds.
 *
 * <p>Ticked about once a second on BOTH the logical server and the logical client. Each tick rescans the
 * sub-levels near the sea band with {@link HoldScanner}, runs the two-scan {@link HoldDebounce} on the volume
 * hash and, when the volume really changed, swaps the Sable water-occlusion region ({@link SableWaterOcclusion})
 * and the envelope everything else reads. Sable's regions are not networked, which is why both sides run their
 * own registry instead of one side telling the other.
 *
 * <p>Reads ({@link #isOccluded}, {@link #activeEnvelope}) are safe from other threads (the physics step reads the
 * envelope for the bilge purge).
 */
public final class HoldRegistry {

    /** Ticks between rescans (about one second). */
    public static final int SCAN_INTERVAL_TICKS = 20;
    /** Only sub-levels whose world AABB reaches into this band can hold sea water. */
    private static final double SEA_BAND_MIN_Y = 56.0;
    private static final double SEA_BAND_MAX_Y = 72.0;
    /** Client: only scan ships within this distance of the focus point (camera / player). */
    public static final double MAX_FOCUS_DISTANCE = 192.0;

    private static final Map<Level, HoldRegistry> INSTANCES = new ConcurrentHashMap<>();

    private static final ThreadLocal<Vector3d[]> SCRATCH =
            ThreadLocal.withInitial(() -> new Vector3d[]{new Vector3d(), new Vector3d()});

    private final Level level;
    private final Map<SubLevel, Entry> entries = new ConcurrentHashMap<>();
    private long lastScanTick = Long.MIN_VALUE;

    private HoldRegistry(Level level) {
        this.level = level;
    }

    private static final class Entry {
        HoldDebounce debounce = new HoldDebounce(0);
        HoldScanner.HullClass lastClass = HoldScanner.HullClass.FLAT_RAFT;
        @Nullable WaterOcclusionRegion region;
        /** The envelope currently applied (Sable region, wave mask, depth planes, purge, physics). */
        volatile @Nullable HoldScanner.Envelope active;
    }

    // =========================================================================================
    //  LOOKUP
    // =========================================================================================

    @Nullable
    public static HoldRegistry peek(Level level) {
        return level == null ? null : INSTANCES.get(level);
    }

    private static HoldRegistry getOrCreate(Level level) {
        return INSTANCES.computeIfAbsent(level, HoldRegistry::new);
    }

    /** Drops a level's registry and its Sable regions (level unload / logout). */
    public static void discard(Level level) {
        final HoldRegistry registry = INSTANCES.remove(level);
        if (registry != null) {
            registry.clear();
        }
    }

    /** The applied envelope of a sub-level, or null if it is not a registered sunken hold. */
    @Nullable
    public static HoldScanner.Envelope activeEnvelope(SubLevel sub) {
        HoldRegistry registry = peek(sub.getLevel());
        Entry entry = registry == null ? null : registry.entries.get(sub);
        if (entry == null) {
            // The sub-level's own level object is not necessarily the level that was ticked: ask every registry.
            for (final HoldRegistry other : INSTANCES.values()) {
                entry = other.entries.get(sub);
                if (entry != null) {
                    break;
                }
            }
        }
        return entry == null ? null : entry.active;
    }

    /** Visits every sub-level with an applied sunken-hold envelope. */
    public void forEachActive(BiConsumer<SubLevel, HoldScanner.Envelope> visitor) {
        for (final Map.Entry<SubLevel, Entry> e : entries.entrySet()) {
            final HoldScanner.Envelope env = e.getValue().active;
            if (env != null) {
                visitor.accept(e.getKey(), env);
            }
        }
    }

    /** Did Sable accept a water-occlusion region for this hold (false: only the mod's own fallback is active)? */
    public boolean hasRegion(SubLevel sub) {
        final Entry entry = entries.get(sub);
        return entry != null && entry.region != null;
    }

    public boolean hasActiveHolds() {
        for (final Entry e : entries.values()) {
            if (e.active != null) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================================
    //  PHYSICS FALLBACK QUERIES
    // =========================================================================================

    /** Is this world position inside the sealed volume of any registered hold of the level? */
    public static boolean isOccluded(Level level, Vec3 worldPos) {
        final HoldRegistry registry = peek(level);
        return registry != null && worldPos != null && registry.contains(worldPos.x, worldPos.y, worldPos.z);
    }

    /** Same test for an entity: its feet position or its bounding-box centre. */
    public static boolean isEntityInDryHold(Level level, Vec3 feet, Vec3 boxCenter) {
        final HoldRegistry registry = peek(level);
        if (registry == null) {
            return false;
        }
        return registry.contains(feet.x, feet.y, feet.z) || registry.contains(boxCenter.x, boxCenter.y, boxCenter.z);
    }

    private boolean contains(double wx, double wy, double wz) {
        Vector3d[] scratch = null;
        for (final Map.Entry<SubLevel, Entry> e : entries.entrySet()) {
            final HoldScanner.Envelope env = e.getValue().active;
            if (env == null) {
                continue;
            }
            final SubLevel sub = e.getKey();
            final BoundingBox3dc box = sub.boundingBox();
            if (box == null || wx < box.minX() - 1.0 || wx > box.maxX() + 1.0
                    || wy < box.minY() - 1.0 || wy > box.maxY() + 1.0
                    || wz < box.minZ() - 1.0 || wz > box.maxZ() + 1.0) {
                continue;
            }
            final Pose3dc pose = sub.logicalPose();
            if (pose == null) {
                continue;
            }
            if (scratch == null) {
                scratch = SCRATCH.get();
            }
            scratch[0].set(wx, wy, wz);
            pose.transformPositionInverse(scratch[0], scratch[1]);
            if (env.contains((int) Math.floor(scratch[1].x), (int) Math.floor(scratch[1].y),
                    (int) Math.floor(scratch[1].z))) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================================
    //  DIAGNOSTICS (client command /serenesailing holddebug)
    // =========================================================================================

    /** Human-readable state of every tracked hold of the level relative to a world position. */
    public static java.util.List<String> describe(Level level, Vec3 world) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        final HoldRegistry registry = peek(level);
        if (registry == null) {
            out.add("no hold registry for this level (no sub-levels scanned yet, or the level has no Sable container)");
            return out;
        }
        int n = 0;
        final Vector3d in = new Vector3d(world.x, world.y, world.z);
        final Vector3d local = new Vector3d();
        for (final Map.Entry<SubLevel, Entry> e : registry.entries.entrySet()) {
            final Entry entry = e.getValue();
            final HoldScanner.Envelope env = entry.active;
            final StringBuilder sb = new StringBuilder("sub-level #").append(++n)
                    .append(": lastScan=").append(entry.lastClass)
                    .append(" applied=").append(env != null && env.isSunken())
                    .append(" sableRegion=").append(entry.region != null);
            if (env != null) {
                final Pose3dc pose = e.getKey().logicalPose();
                pose.transformPositionInverse(in, local);
                final int lx = (int) Math.floor(local.x), ly = (int) Math.floor(local.y), lz = (int) Math.floor(local.z);
                final int row = lz - env.minZ();
                sb.append(" rows=").append(env.validRows())
                        .append(" localCell=(").append(lx).append(',').append(ly).append(',').append(lz).append(')');
                if (row >= 0 && row < env.depth()) {
                    sb.append(" rowSpan=").append(env.portX(row)).append("..").append(env.starboardX(row));
                }
                sb.append(" columnY=").append(env.columnLo(lx, lz)).append("..").append(env.columnHi(lx, lz))
                        .append(" inside=").append(env.contains(lx, ly, lz));
                int water = 0;
                for (int z = env.volumeMinZ(); z <= env.volumeMaxZ(); z++) {
                    for (int x = env.volumeMinX(); x <= env.volumeMaxX(); x++) {
                        for (int y = env.columnLo(x, z); y <= env.columnHi(x, z); y++) {
                            if (level.hasChunk(x >> 4, z >> 4)
                                    && !level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).getFluidState().isEmpty()) {
                                water++;
                            }
                        }
                    }
                }
                sb.append(" waterCellsInsideVolume=").append(water);
            }
            out.add(sb.toString());
        }
        if (n == 0) {
            out.add("registry exists but tracks no sub-levels near the sea band");
        }
        return out;
    }

    // =========================================================================================
    //  TICK
    // =========================================================================================

    /**
     * Rescans this level's sub-levels about once a second.
     *
     * @param level the level, server or client
     * @param focus client: camera / player position to limit scanning to nearby ships; server: null (all ships)
     */
    public static void tick(Level level, @Nullable Vec3 focus) {
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            discard(level);
            return;
        }
        getOrCreate(level).tickInternal(container, focus);
    }

    private void tickInternal(SubLevelContainer container, @Nullable Vec3 focus) {
        final long now = level.getGameTime();
        if (lastScanTick != Long.MIN_VALUE && now >= lastScanTick && now - lastScanTick < SCAN_INTERVAL_TICKS) {
            return;
        }
        lastScanTick = now;

        final Vector3d scratch = SCRATCH.get()[0];
        final java.util.Set<SubLevel> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (final SubLevel sub : container.getAllSubLevels()) {
            if (level.isClientSide && sub instanceof ClientSubLevel client && !client.isFinalized()) {
                continue;
            }
            seen.add(sub);
            if (!isNearSeaBand(sub, focus)) {
                // Left the sea band (or the client's range): release the region, rescan when it comes back.
                final Entry stale = entries.get(sub);
                if (stale != null && (stale.active != null || stale.region != null)) {
                    release(stale);
                    stale.debounce = new HoldDebounce(0);
                    stale.lastClass = HoldScanner.HullClass.FLAT_RAFT;
                }
                continue;
            }
            final Entry entry = entries.computeIfAbsent(sub, k -> new Entry());
            final HoldScanner.Envelope env =
                    HoldBlocks.scan(level, sub, sub.logicalPose(), entry.lastClass, scratch);
            if (env == null) {
                continue; // cannot scan right now: keep whatever is applied
            }
            entry.lastClass = env.hullClass();
            if (entry.debounce.offer(env.volumeHash())) {
                apply(entry, sub, env);
            }
        }

        // Sub-levels that vanished (disassembled / unloaded): release their regions.
        for (final Iterator<Map.Entry<SubLevel, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<SubLevel, Entry> e = it.next();
            if (!seen.contains(e.getKey())) {
                release(e.getValue());
                it.remove();
            }
        }
    }

    private void apply(Entry entry, SubLevel sub, HoldScanner.Envelope env) {
        SableWaterOcclusion.remove(level, entry.region);
        entry.region = null;
        if (env.isSunken()) {
            entry.region = SableWaterOcclusion.add(level, env);
            entry.active = env;
        } else {
            entry.active = null;
        }
    }

    private void release(Entry entry) {
        SableWaterOcclusion.remove(level, entry.region);
        entry.region = null;
        entry.active = null;
    }

    private void clear() {
        for (final Entry e : entries.values()) {
            release(e);
        }
        entries.clear();
    }

    private static boolean isNearSeaBand(SubLevel sub, @Nullable Vec3 focus) {
        final BoundingBox3dc box = sub.boundingBox();
        if (box == null || box.maxY() < SEA_BAND_MIN_Y || box.minY() > SEA_BAND_MAX_Y) {
            return false;
        }
        if (focus == null) {
            return true;
        }
        final double dx = Math.max(Math.max(box.minX() - focus.x, focus.x - box.maxX()), 0.0);
        final double dz = Math.max(Math.max(box.minZ() - focus.z, focus.z - box.maxZ()), 0.0);
        return dx * dx + dz * dz <= MAX_FOCUS_DISTANCE * MAX_FOCUS_DISTANCE;
    }
}

