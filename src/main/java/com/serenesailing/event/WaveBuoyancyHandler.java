package com.serenesailing.event;

import com.serenesailing.SereneSailing;
import com.serenesailing.physics.waves.WaveSurface;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.joml.Vector3d;

/**
 * Couples entities to the Gerstner surface on BOTH logical sides.
 *
 * <p>The hard part of the coupling (what counts as "in water", where the waterline is) is done by
 * {@code BoatWaveMixin} and {@code EntityWaveFluidMixin}. This handler adds the dynamics vanilla cannot
 * express: following the surface's vertical <i>velocity</i> (so a boat or swimmer rides a rising crest instead of
 * being overtaken by it) and a gentle downslope drift.
 *
 * <p>Each entity is handled only where its physics actually runs ((see {@link #isAuthoritative}): the
 * pilot's / swimmer's own client for a player, the server for mobs, items and unmanned boats. That is what keeps
 * a client-driven boat from rubber-banding: the client that moves it and the server that validates it
 * evaluate the same {@link WaveSurface} and agree on where the water is.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID)
public final class WaveBuoyancyHandler {

    /**
     * vanilla floatBoat() damps vertical velocity by 0.75 each tick, so a per-tick impulse f settles at
     * v = f * 0.75 / 0.25 = 3f. Feeding f = dSurface/3 therefore tracks the surface's own vertical speed.
     */
    private static final double BOAT_FEED_FORWARD = 1.0 / 3.0;
    private static final double BOAT_MAX_VERTICAL_IMPULSE = 0.08;
    /** Fraction of g*sin(theta) applied as downslope acceleration [blocks/tick^2 per unit slope]. */
    private static final double BOAT_SLOPE_DRIFT = 0.0245 * 0.25;
    private static final double BOAT_MAX_DRIFT_IMPULSE = 0.01;

    private WaveBuoyancyHandler() {}

    /**
     * Is this logical side the one that simulates this entity's movement?
     * Players: only their own client. Boats: whichever side {@code Boat.tick()} itself runs floatBoat() on
     * (the pilot's client, or the server when unmanned). Everything else (mobs, items): the server.
     */
    private static boolean isAuthoritative(Entity e) {
        if (e instanceof Player p) {
            return p.isLocalPlayer();
        }
        if (e instanceof Boat b) {
            return b.isControlledByLocalInstance();
        }
        return !e.level().isClientSide();
    }

    private static boolean eligible(Entity e) {
        return isAuthoritative(e) && !e.isRemoved() && WaveSurface.isEntityInWaveZone(e);
    }

    /** Vertical speed of the surface above this entity [blocks/tick], sampled where the entity will be next tick. */
    private static double surfaceRiseNextTick(Entity e, Vec3 dm) {
        Level level = e.level();
        double now = WaveSurface.surfaceY(level, e.getX(), e.getZ(), 0.0f);
        double next = WaveSurface.surfaceY(level, e.getX() + dm.x, e.getZ() + dm.z, 1.0f);
        return next - now;
    }

    // ---- Boats: before Boat.tick() so floatBoat()/move() consume the corrected velocity -----------------

    @SubscribeEvent
    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Boat boat) || !eligible(boat)) {
            return;
        }
        double surface = WaveSurface.surfaceY(boat.level(), boat.getX(), boat.getZ(), 0.0f);
        double keel = boat.getBoundingBox().minY;
        // Only while in contact with the surface; a boat that has been launched off a crest is left to gravity.
        if (keel > surface + 0.4 || keel < surface - 2.0) {
            return;
        }

        Vec3 dm = boat.getDeltaMovement();
        double vy = Mth.clamp(surfaceRiseNextTick(boat, dm) * BOAT_FEED_FORWARD,
                -BOAT_MAX_VERTICAL_IMPULSE, BOAT_MAX_VERTICAL_IMPULSE);

        // Slide gently down the face of the wave: the horizontal part of the surface normal points downhill.
        Vector3d n = WaveSurface.normal(boat.level(), boat.getX(), boat.getZ(), 0.0f, new Vector3d());
        double slope = Math.sqrt(n.x * n.x + n.z * n.z) / Math.max(n.y, 1.0e-3);
        double dx = 0.0;
        double dz = 0.0;
        if (slope > 1.0e-4) {
            double a = Math.min(BOAT_SLOPE_DRIFT * slope, BOAT_MAX_DRIFT_IMPULSE);
            double inv = 1.0 / Math.sqrt(n.x * n.x + n.z * n.z);
            dx = n.x * inv * a;
            dz = n.z * inv * a;
        }
        boat.setDeltaMovement(dm.x + dx, dm.y + vy, dm.z + dz);
    }

    // ---- Swimmers and items: after the tick, nudge them along with the surface ---------------------------

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        Entity e = event.getEntity();
        boolean swimmer = e instanceof LivingEntity && !(e instanceof Boat);
        if (!swimmer && !(e instanceof ItemEntity)) {
            return;
        }
        if (e.isPassenger() || e.onGround() || !e.isInWater() || !eligible(e)) {
            return;
        }
        if (e instanceof Player p && (p.getAbilities().flying || p.isSpectator())) {
            return;
        }
        if (e instanceof LivingEntity le && le.isCrouching()) {
            return; // deliberate dive
        }

        Vec3 dm = e.getDeltaMovement();
        double surface = WaveSurface.surfaceY(e.level(), e.getX(), e.getZ(), 0.0f);
        double reference = (e instanceof ItemEntity) ? e.getY() : e.getEyeY();
        double below = surface - reference;
        // Only entities at the surface (just under it, or bobbing just above it) ride it; deep divers do not.
        if (below < -0.6 || below > 1.2) {
            return;
        }
        double rise = surfaceRiseNextTick(e, dm);
        // Blend toward the surface's vertical speed instead of replacing the entity's own motion.
        double vy = dm.y + Mth.clamp((rise - dm.y) * 0.5, -0.08, 0.08);
        e.setDeltaMovement(dm.x, vy, dm.z);
    }
}

