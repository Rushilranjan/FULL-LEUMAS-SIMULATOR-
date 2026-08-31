package com.leumas.robot.render3d

import com.leumas.robot.calibration.EdgePoint
import com.leumas.robot.render.Vec2
import com.leumas.robot.render.lerp
import kotlin.math.atan2
import kotlin.math.hypot

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

    // Arm segment lengths in world units (lower + upper give the vertical reach; forearm is held
    // horizontal by the parallelogram and adds pure horizontal extension).
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
     */
    const val BOTTOM_Y = -0.26f

    /**
     * How far in front of the base (toward the camera) the reach plane sits.
     *
     * Non-zero so the arm works in front of its own turret rather than edge-on, and so a module
     * directly above the carriage still yields a well-conditioned yaw instead of `atan2(0, 0)`.
     */
    const val REACH_DEPTH = 0.40f

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
     * Directly under the target where the rail can get there, clamped to the travel limit otherwise.
     * The clamp is what creates the diagonals: for a corner module the carriage runs out of rail and
     * the base has to turn to make up the remaining offset.
     */
    fun carriageXFor(point: EdgePoint): Float =
        worldTargetFor(point).x.coerceIn(-RAIL_HALF, RAIL_HALF)

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
        return clampToReach(
            Vec2(
                x = hypot(dx, REACH_DEPTH),
                y = SHOULDER_Y - target.y,
            )
        )
    }

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
