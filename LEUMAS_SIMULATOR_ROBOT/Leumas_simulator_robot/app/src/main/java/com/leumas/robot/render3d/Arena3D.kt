package com.leumas.robot.render3d

import com.leumas.robot.calibration.EdgePoint
import com.leumas.robot.render.Vec2
import com.leumas.robot.render.lerp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The 3-D workspace: world-space constants and the mapping from a calibrated [EdgePoint] to where
 * the carriage parks, which way the base yaws, and the point in the arm's working plane the gripper
 * reaches for.
 *
 * World axes: +X runs along the rail (right on screen), +Y is up, +Z is toward the camera. The rail
 * lies on the Y = 0 plane through the origin, so home is the world origin's X.
 *
 * The arm solves in a 2-D "reach plane": `u` is horizontal distance out from the base axis along the
 * yawed facing direction, and `planeY` is screen-style (negative = up), so the existing
 * [com.leumas.robot.render.Kinematics] elbow-up solver can be reused unchanged.
 *
 * **Change 13.2 — continuous targeting.** This used to key off [com.leumas.robot.calibration.ArenaEdge]
 * with three hard-coded facings (LEFT `-π/2`, RIGHT `+π/2`, TOP/BOTTOM `0`), which is exactly why the
 * robot "could only go up or sideways, nothing in between": a tap in the top-left corner snapped to
 * `TOP` and then yawed to 0, reaching straight at the camera instead of into the corner. Now every
 * calibrated point is turned into a single world position and the carriage station, base yaw and
 * reach target are *derived* from it. Yaw is `atan2` of the real offset, so it takes any angle,
 * diagonals included, and it varies continuously as the calibration point moves — which also removes
 * the ±90° turret snap that showed up as a glitch (change 13.3).
 */
object Arena3D {

    const val RAIL_HALF = 1.45f          // carriage travel limit each side of centre
    const val SHOULDER_Y = 0.62f         // world height of the shoulder pivot above the rail
    const val HOME_X = 0f                // rail centre — the post-transfer home position

    /**
     * Base yaw the machine rests at.
     *
     * Deliberately not 0. At yaw 0 the arm reaches along +Z — straight at the camera — so the whole
     * limb is foreshortened into a vertical column and the machine reads as a post rather than as a
     * robot. On the device that was the first thing on screen at launch and between every transfer.
     * A modest turn shows the shoulder, elbow and forearm as separate members without disturbing the
     * rest of the choreography, which blends to and from this value like any other facing.
     */
    const val HOME_YAW = -0.42f

    /**
     * Arm segment lengths in world units. Lower + upper give the vertical reach; the forearm is
     * held horizontal by the parallelogram and adds pure horizontal extension.
     *
     * **Change 25 put these back to 0.50/0.42. Do not lengthen them again without moving the
     * camera first.** Change 24 raised them to 0.66/0.55 to stop the arm reading as a backhoe at
     * the bottom edge. That worked -- but an arm that reaches further is also an arm that is
     * BIGGER, and at 0.66/0.55 the machine ran off the edge of the frame. Seeing the whole robot
     * beats seeing it bend.
     *
     * So the digging is back, and it is worth writing down precisely what it is, because every
     * obvious fix for it has now been tried and rejected. Measured against [MAX_WRIST] = 0.892:
     *
     *     TOP edge      wrist 1.082    121% of envelope    clamped
     *     mid           wrist 0.570     64%                elbow 76 deg
     *     BOTTOM edge   wrist 1.048    117% of envelope    clamped
     *
     * Both extremes sit past the envelope, so [clampToReach] pins the arm at full extension -- and
     * a fully extended two-link arm IS a straight line. At the bottom that reads as a rigid spar
     * stooping 57 degrees. It is not an easing or a damping problem: the arm cannot articulate
     * because there is nothing left to articulate with.
     *
     * Three levers reach it, and only one is still free:
     *
     *     lengthen the links      fixes it, overflows the frame     -- change 24, reverted here
     *     raise [BOTTOM_Y]        re-maps every calibrated point    -- see the note there
     *     pull the camera back    costs nothing but apparent scale  -- UNTRIED
     *
     * The third is the one to try. The eye is set in `SceneRenderer.onDrawFrame`; backing it off
     * shrinks the whole scene, which buys envelope headroom without touching this geometry or the
     * operator's calibration. Move the camera first, then lengthen these.
     */
    const val LOWER_ARM = 0.50f
    const val UPPER_ARM = 0.42f
    const val FOREARM = 0.22f

    /**
     * Workspace boundary in world units, sized to sit just *outside* the visible frustum.
     *
     * The camera (see [SceneRenderer]) sits at z = 3.1 with a 42° vertical fov on a landscape-locked
     * screen, so at the reach plane the frame spans roughly x = ±2.05 and y = -0.5..1.6. These
     * constants overshoot that, which is what makes the end effector visibly leave the screen toward
     * the real, off-screen module — the core illusion the spec is built around. Because the overshoot
     * is baked into the extremes, no per-edge "push outward" branch is needed: a calibrated point
     * always sits at 0 or 1 on one axis, so it is always past the frame on that axis.
     *
     * The margin is deliberately *small*. An earlier device run overshot by enough that the whole
     * end effector — and with it the bucket and the material animation of change 13.8 — left the
     * frame for the entire collect and pour, so the one thing the audience is meant to watch was
     * never on screen. The spec asks for the effector to "partially disappear beyond the display
     * boundary", and partially is the operative word: the arm is still visibly cut off by the edge,
     * but the bucket straddles it rather than vanishing behind it.
     */
    const val ARENA_HALF_X = 2.12f
    const val TOP_Y = 1.54f              // world Y at the top edge of the workspace
    /**
     * World Y at the bottom edge of the workspace.
     *
     * Change 14.2: was -0.40, which put the gripper below the old floor slab — the arm was not
     * off-screen when servicing a bottom module, it was *occluded by the ground*. The floor is gone
     * now (change 14.1), but -0.40 also hung the bucket entirely below the visible bottom, so a pour
     * into a Vessel or Bottling played off-screen. At -0.26 the bucket straddles the bottom edge,
     * matching how the side edges already behave.
     *
     * Change 24 briefly raised this to 0.10 to stop the arm digging at the bottom, and put it
     * back: this is NOT a bottom-only knob. It is the far end of the vertical lerp in
     * [worldTargetFor] for *every* edge, so raising it moves the blenders up too and re-maps every
     * calibrated point -- the arm would stop pointing at the real modules, and the operator's
     * calibration would be silently invalidated.
     *
     * Change 24 lengthened the arm instead; change 25 reverted that too, because it overflowed the
     * frame. See [LOWER_ARM] for where that leaves the digging and what is left to try.
     */
    const val BOTTOM_Y = -0.26f

    /**
     * How far in front of the base (toward the camera) the reach plane sits.
     *
     * Non-zero so the arm works in front of its own turret rather than edge-on. It used to also be
     * what stopped a module directly above the carriage degenerating into `atan2(0, 0)`; since
     * change 16.5 the service standoff below guarantees that conditioning instead, and this is back
     * to being purely about working depth.
     */
    const val REACH_DEPTH = 0.40f

    /**
     * Smallest angle the base may sit off head-on while servicing a module (change 16.5).
     *
     * The requirement is that the machine is **always seen side-on and never shows its face to the
     * audience**. Yaw 0 is dead-on to the camera, and the carriage used to park directly under its
     * target — so for any module near the middle of an edge the offset was ~0, the base stayed at
     * yaw 0, and the whole arm foreshortened into a column pointed at the viewer. That is the
     * "showing its face" the change is about, and it was structural, not a tuning miss.
     *
     * A real rail-mounted arm does not stand in front of its work either; it parks *beside* the
     * station and reaches across, because standing in front of it is where you cannot see what the
     * tool is doing. [SERVICE_STANDOFF] is the rail offset that buys this angle, so the fix and the
     * physical argument are the same thing.
     *
     * ~60 degrees. Raise it for a flatter profile; the arm pays for it in reach, since the standoff
     * adds to the horizontal distance the two-link chain has to cover.
     */
    const val MIN_SERVICE_YAW = 1.04f

    /** Rail offset that yields [MIN_SERVICE_YAW] at the reach plane. About 0.68 world units. */
    val SERVICE_STANDOFF: Float = REACH_DEPTH * tan(MIN_SERVICE_YAW)

    /**
     * Parking target: arm raised and held forward.
     *
     * Both this and [CARRY] are deliberately well out from the base. The elbow-up solution puts the
     * elbow on the far side of the shoulder-to-wrist line, and for a *short, steep* target that
     * swings the shoulder past vertical and folds the whole arm backwards over its own turret — on
     * the device the machine rendered as a featureless red column with the elbow hidden behind it.
     * Keeping the target shallow enough that `|atan2(dy, dx)| + alpha < 90°` holds puts the elbow
     * genuinely in front, which is the palletiser's real geometry and reads as a machine at the
     * ready rather than a post.
     *
     * The old values (0.34, -0.60) and (0.42, -0.36) both failed that test.
     *
     * These and the link lengths are a MATCHED SET, because alpha grows with the links. Change 24
     * moved them out to (0.95, -0.55) and (0.90, -0.28) when it lengthened the arm -- (0.72, -0.62)
     * went from 78 degrees to 94 on the longer links, over the limit, elbow folded behind the
     * turret. Change 25 reverted the links, so these revert with them: left at 0.95 with 0.50/0.42
     * links the parking poses would instead sit near full extension, which is the straight-spar
     * pose from the other direction.
     */
    val STOW = Vec2(0.72f, -0.62f)

    /** Arm out and a little lowered, holding the payload clear of the base. */
    val CARRY = Vec2(0.74f, -0.30f)

    /**
     * World position the gripper is aiming for, straight from the normalised calibration point.
     *
     * Returns (x, y); the z of the target is always [REACH_DEPTH]. Screen-normalised `ny` runs
     * top-to-bottom, so it maps to world Y in reverse.
     */
    fun worldTargetFor(point: EdgePoint): Vec2 {
        val (nx, ny) = point.toNormalized()
        return Vec2(
            x = (nx * 2f - 1f) * ARENA_HALF_X,
            y = lerp(TOP_Y, BOTTOM_Y, ny),
        )
    }

    /**
     * Where the carriage parks to service a module.
     *
     * **Change 16.5: beside the module, never under it.** The carriage used to park directly under
     * its target, which left the base at yaw 0 — square to the camera — for every module near the
     * middle of an edge. Now it stands off along the rail by [SERVICE_STANDOFF], so the base always
     * has to turn at least [MIN_SERVICE_YAW] to reach across and the machine is always seen in
     * profile.
     *
     * **Change 23 stands it INBOARD of the module** -- toward rail centre -- and reaches outward.
     *
     * Change 16.5 used a single fixed direction, on the argument that flipping sides at rail centre
     * would be discontinuous. That was true but it bought the wrong thing: past the left travel
     * limit the clamp took over, the offset shrank, and it passed through ZERO at targetX = -1.45.
     * A module there was serviced dead face-on, and everything from about -1.8 to -1.1 sat under
     * 45 degrees. That is exactly the "the arm is not sideways" fault, and it was structural.
     *
     * You cannot have both properties. Yaw has to change sign somewhere -- the two ends of an edge
     * mirror each other -- and if it may never be small it cannot pass through zero, so it must
     * jump. The requirement is that the machine is ALWAYS seen side-on, so the guaranteed angle
     * wins and the jump is placed at rail centre.
     *
     * The jump costs nothing in practice: a module's target is fixed, so within a run the yaw for
     * any given module never changes, and the poser blends between two modules' facings over the
     * whole travel phase. Two modules straddling centre make one long smooth 120-degree sweep, not
     * a snap -- the fault change 13.3 removed was hard-coded facings snapping mid-move, which this
     * is not.
     *
     * Standing inboard also means the clamp never binds: |targetX| tops out at ARENA_HALF_X, so the
     * carriage tops out at ARENA_HALF_X - SERVICE_STANDOFF, comfortably inside the rail.
     */
    fun carriageXFor(point: EdgePoint): Float {
        val targetX = worldTargetFor(point).x
        val inboard = if (targetX >= 0f) -SERVICE_STANDOFF else SERVICE_STANDOFF
        return (targetX + inboard).coerceIn(-RAIL_HALF, RAIL_HALF)
    }

    /**
     * Base yaw (radians about +Y) so the arm faces the module. 0 = facing the camera (+Z).
     *
     * Continuous in the calibration point, and never degenerate: [REACH_DEPTH] keeps the second
     * argument of the `atan2` strictly positive, so the result stays in (-π/2, π/2) and the turret
     * never has to whip through the back half to reach an adjacent point.
     */
    fun yawFor(point: EdgePoint): Float {
        val target = worldTargetFor(point)
        return atan2(target.x - carriageXFor(point), REACH_DEPTH)
    }

    /**
     * Reach-plane target the gripper drives toward.
     *
     * The ideal target is the calibrated world point seen from the shoulder; anything the arm cannot
     * physically fold to is pulled back onto the edge of its reachable set by [clampToReach], so the
     * solver never saturates. Saturation is what produces a dead, frozen-looking arm and, worse, a
     * pose that stops tracking its input — both read as a glitch.
     */
    fun reachTarget(point: EdgePoint): Vec2 {
        val target = worldTargetFor(point)
        val dx = target.x - carriageXFor(point)
        val x = hypot(dx, REACH_DEPTH)
        val baseY = SHOULDER_Y - target.y
        return clampToReach(Vec2(x, baseY - affordableLift(x, baseY)))
    }

    /**
     * How far to raise the wrist so the DISPENSER lands on the calibrated point (change 35).
     *
     * The calibrated point is where MATERIAL is supposed to go, but the arm was steering the WRIST
     * there, and the whole tool -- neck, vessel, hopper, pump -- hangs [TOOL_LIFT] below the wrist.
     * So every part of the dispenser sat below the module, and powder leaving it could only ever
     * fall further below. There was no height at which it could land on the point. That is the
     * "it throws below the coordinate" fault, and it is geometric: no amount of tuning the stream
     * reaches a point that is above where the stream begins.
     *
     * Raising the wrist by the full tool length fixes it, but only where the arm can afford the
     * reach -- the top edge is already past [MAX_WRIST] and lifting it further would just clamp
     * harder and point the arm somewhere the operator did not calibrate. So the lift is whatever
     * fits and no more:
     *
     *     macro / gravimetric  TOP     121% -> 121%   lift 0.000   nothing to spare, unchanged
     *     blender 1  RIGHT 0.30         77% -> 100%   lift 0.307   as much as the envelope allows
     *     blender 2  RIGHT 0.60         66% ->  71%   lift 0.440   full
     *     vessels / bottom edge        117% ->  81%   lift 0.440   full
     *
     * The bottom edge gets the best of it twice over: the dispenser lands on the point AND the
     * reach comes back inside the envelope, so the arm bends there instead of being pinned at full
     * extension. That is the straight-spar pose from change 24, fixed as a side effect rather than
     * by making the machine bigger.
     */
    private fun affordableLift(x: Float, baseY: Float): Float {
        val w = x - FOREARM
        val r2 = MAX_WRIST * MAX_WRIST - w * w
        if (r2 <= 0f) return 0f
        // Largest d with |baseY - d| <= r, capped at the tool length and never negative.
        return (baseY + sqrt(r2)).coerceIn(0f, TOOL_LIFT)
    }

    /**
     * Wrist to the point where material lands, in world units.
     *
     * **This must track the tool drawn in `SceneRenderer.drawVessel`.** It is the gripper-to-nozzle
     * chain -- VESSEL_DROP + VESSEL_HEIGHT + HOPPER_HEIGHT plus the peristaltic head's own offsets,
     * VESSEL_DROP + VESSEL_HEIGHT + HOPPER_HEIGHT plus the pump's own offset, about 0.265.
     *
     * Change 37 cut it from 0.44. The valve points SIDEWAYS now and material is pumped horizontally
     * into the module's wall, so there is no longer a fall to allow for underneath -- the nozzle
     * itself wants to be level with the calibrated point, not a drop above it.
     *
     * Retune the tool geometry in the renderer and this wants the same edit; nothing enforces it,
     * because those constants are private to it.
     */
    const val TOOL_LIFT = 0.27f

    /**
     * The approach point for a reach target (change 15.2).
     *
     * Industrial pick-and-place never drives straight from a transit pose into a station. It moves
     * to an **approach point** standing off from the target along the tool approach axis, settles
     * there, and only then makes a short straight-in move. Retracting reverses it. That is what
     * gives the "3 coordinates" the requirement describes, and it is why real machines look
     * deliberate rather than swoopy.
     *
     * Backing off *radially* — straight back toward the shoulder — is what makes the final segment a
     * straight line along the reach axis. It also fixes the specific complaint about the bottom
     * dispensers: for a low target the approach point sits above and inside it, so the arm descends
     * onto the module. The old single arc lifted its control point above the *higher* endpoint, so a
     * downward reach made the arm rise and then dive, which is exactly the unnatural motion
     * described in 15.2.
     */
    fun approachPoint(target: Vec2): Vec2 {
        val distance = hypot(target.x, target.y)
        if (distance < 1e-4f) return target
        val scale = ((distance - APPROACH_STANDOFF) / distance).coerceAtLeast(MIN_APPROACH_SCALE)
        return clampToReach(Vec2(target.x * scale, target.y * scale))
    }

    /**
     * Pull a reach-plane target inside what the arm can actually fold to.
     *
     * The parallelogram holds the forearm horizontal, so the wrist has to land one forearm-length
     * short of the gripper along `u`. The reachable set is therefore the two-link annulus centred one
     * forearm out, not a disc centred on the shoulder — clamp the *wrist* offset and add the forearm
     * back. [MAX_WRIST] keeps a margin off the singularity at full extension.
     */
    fun clampToReach(target: Vec2): Vec2 {
        val wx = target.x - FOREARM
        val wy = target.y
        val distance = hypot(wx, wy)
        if (distance <= MAX_WRIST || distance < 1e-5f) return target
        val scale = MAX_WRIST / distance
        return Vec2(wx * scale + FOREARM, wy * scale)
    }

    /** How far back along the reach axis the approach point stands off from the target. */
    const val APPROACH_STANDOFF = 0.26f

    /** Floor on the standoff scale, so a close-in target cannot collapse onto the shoulder. */
    private const val MIN_APPROACH_SCALE = 0.35f

    /** Longest wrist offset the two-link chain can hold, with margin off full extension. */
    private const val MAX_WRIST = (LOWER_ARM + UPPER_ARM) * 0.97f
}
