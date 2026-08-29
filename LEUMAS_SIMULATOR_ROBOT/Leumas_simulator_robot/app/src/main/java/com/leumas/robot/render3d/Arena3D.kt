package com.leumas.robot.render3d

import com.leumas.robot.calibration.ArenaEdge
import com.leumas.robot.calibration.EdgePoint
import com.leumas.robot.render.Vec2

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
 * Reach targets are pushed deliberately past the visible frustum so the gripper leaves the screen
 * toward the real, off-screen module — the core illusion the spec is built around.
 */
object Arena3D {

    const val RAIL_HALF = 1.45f          // carriage travel limit each side of centre
    const val SHOULDER_Y = 0.62f         // world height of the shoulder pivot above the rail
    const val HOME_X = 0f                // rail centre — the post-transfer home position

    // Arm segment lengths in world units (lower + upper give the vertical reach; forearm is held
    // horizontal by the parallelogram and adds pure horizontal extension).
    const val LOWER_ARM = 0.50f
    const val UPPER_ARM = 0.42f
    const val FOREARM = 0.22f

    /** Folded, upright parking target (arm tucked up and slightly forward). */
    val STOW = Vec2(0.12f, -0.55f)

    /** Arm out and a little lowered, holding the payload clear of the base. */
    val CARRY = Vec2(0.22f, -0.34f)

    /** Where the carriage parks to service a module on [point]'s edge. */
    fun carriageXFor(point: EdgePoint): Float = when (point.edge) {
        ArenaEdge.LEFT -> -RAIL_HALF
        ArenaEdge.RIGHT -> RAIL_HALF
        ArenaEdge.TOP, ArenaEdge.BOTTOM -> ((point.position * 2f - 1f) * RAIL_HALF)
    }

    /** Base yaw (radians about +Y) so the arm faces the module. 0 = facing the camera (+Z). */
    fun yawFor(point: EdgePoint): Float = when (point.edge) {
        ArenaEdge.LEFT -> -HALF_PI
        ArenaEdge.RIGHT -> HALF_PI
        ArenaEdge.TOP, ArenaEdge.BOTTOM -> 0f
    }

    /** Reach-plane target the gripper drives toward — past the frustum edge, so it exits screen. */
    fun reachTarget(point: EdgePoint): Vec2 = when (point.edge) {
        // Reach straight out along the rail; carriage is already at the end, so the gripper ends up
        // well beyond the side of the frame.
        ArenaEdge.LEFT, ArenaEdge.RIGHT -> Vec2(0.95f, -0.05f)
        // Raise high and slightly forward so the end effector climbs off the top of the frame.
        // Kept just inside the arm's reach (lower+upper = 0.92) so the joints fully articulate
        // toward it rather than saturating at full extension.
        ArenaEdge.TOP -> Vec2(0.40f, -0.80f)
        // Reach low and toward the camera so it drops off the bottom of the frame.
        ArenaEdge.BOTTOM -> Vec2(0.85f, 0.28f)
    }

    private const val HALF_PI = (Math.PI / 2.0).toFloat()
}
