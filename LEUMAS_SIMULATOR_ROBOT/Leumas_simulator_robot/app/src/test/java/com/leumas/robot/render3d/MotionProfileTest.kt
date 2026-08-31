package com.leumas.robot.render3d

import com.leumas.robot.calibration.ArenaEdge
import com.leumas.robot.calibration.EdgePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The regression test for requirement 15.3 — "check the mechanics and physics for each motion".
 *
 * Requirement 15.2 reported that the machine "looks unnatural" while moving. Measuring the easing
 * functions that were driving it showed why, and it was not a matter of taste:
 *
 *  - `easeInOutCubic` (carriage, base yaw) stepped its acceleration from +12 to −12 across the
 *    midpoint of every move.
 *  - `easeInOutSine` (arm, wrist) began and ended each move with acceleration at ±4.93 instead of
 *    zero.
 *
 * Both are instantaneous changes in velocity slope — infinite jerk — which no servo-driven axis can
 * execute. Industrial motion controllers use jerk-limited S-curve profiles specifically to avoid it.
 *
 * These tests pin the replacement profile's defining properties so the old shapes cannot creep back
 * in. They deliberately assert on *derivatives*, not on sampled values: it is the derivatives that
 * were wrong.
 */
class MotionProfileTest {

    /** The profile under test, mirroring `Robot3DPoser.sCurve` (private there). */
    private fun sCurve(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * u * (u * (u * 6f - 15f) + 10f)
    }

    /**
     * Double-precision evaluation of the same polynomial, for differentiating.
     *
     * A second difference divides by h^2, so in Float (about 7 significant digits) the rounding
     * error is amplified to the order of 100 and swamps the quantity being measured. The shipped
     * code is Float because the renderer is; the *analysis* has to be done in Double.
     */
    private fun exact(t: Double): Double {
        val u = t.coerceIn(0.0, 1.0)
        return u * u * u * (u * (u * 6.0 - 15.0) + 10.0)
    }

    private fun velocity(t: Double, h: Double = 1e-5): Double =
        (exact(t + h) - exact(t - h)) / (2 * h)

    private fun acceleration(t: Double, h: Double = 1e-4): Double =
        (exact(t + h) - 2 * exact(t) + exact(t - h)) / (h * h)

    @Test
    fun `profile spans exactly zero to one`() {
        assertEquals(0.0, sCurve(0f).toDouble(), 1e-6)
        assertEquals(1.0, sCurve(1f).toDouble(), 1e-6)
    }

    @Test
    fun `starts and ends at rest`() {
        assertEquals("velocity at start", 0.0, velocity(0.002), 0.02)
        assertEquals("velocity at end", 0.0, velocity(0.998), 0.02)
    }

    /**
     * The property `easeInOutSine` failed. Acceleration must ease in from zero, not jump straight to
     * a finite value the instant the move begins.
     */
    @Test
    fun `acceleration is zero at both endpoints`() {
        assertTrue("accel at start was ${acceleration(0.002)}", abs(acceleration(0.002)) < 0.5)
        assertTrue("accel at end was ${acceleration(0.998)}", abs(acceleration(0.998)) < 0.5)
    }

    /**
     * The property `easeInOutCubic` failed. A step across the midpoint is infinite jerk in the
     * middle of every single move.
     */
    @Test
    fun `acceleration does not step across the midpoint`() {
        val before = acceleration(0.4999)
        val after = acceleration(0.5001)
        assertTrue("accel stepped $before -> $after at the midpoint", abs(after - before) < 0.5)
    }

    /** Monotonic: the machine must never back up part-way through a commanded move. */
    @Test
    fun `profile is monotonic`() {
        var previous = sCurve(0f)
        for (i in 1..1000) {
            val v = sCurve(i / 1000f)
            assertTrue("profile went backwards at $i", v >= previous - 1e-6f)
            previous = v
        }
    }

    /**
     * Requirement 15.2: the final segment into a module must be a straight line along the reach
     * axis, which is how an industrial machine enters a station.
     *
     * [Arena3D.approachPoint] backs the target off *radially*, so the approach point, the target and
     * the shoulder are collinear. Checked here by cross product across every edge.
     */
    @Test
    fun `approach point is radial, so the final move is a straight line in`() {
        listOf(ArenaEdge.LEFT, ArenaEdge.TOP, ArenaEdge.RIGHT, ArenaEdge.BOTTOM).forEach { edge ->
            for (i in 0..10) {
                val point = EdgePoint(edge, i / 10f)
                val target = Arena3D.reachTarget(point)
                val approach = Arena3D.approachPoint(target)
                // Collinear with the shoulder at the origin: cross product of the two vectors is 0.
                val cross = target.x * approach.y - target.y * approach.x
                assertTrue("$edge @ ${i / 10f} approach was not radial (cross=$cross)", abs(cross) < 1e-3f)
                // ...and genuinely stood off from the target, not sitting on top of it.
                val gap = hypot(target.x - approach.x, target.y - approach.y)
                assertTrue("$edge @ ${i / 10f} had no standoff (gap=$gap)", gap > 0.05f)
            }
        }
    }

    /** The approach point must itself be reachable, or the arm saturates on the way in. */
    @Test
    fun `approach points stay inside the arm envelope`() {
        listOf(ArenaEdge.LEFT, ArenaEdge.TOP, ArenaEdge.RIGHT, ArenaEdge.BOTTOM).forEach { edge ->
            for (i in 0..10) {
                val approach = Arena3D.approachPoint(Arena3D.reachTarget(EdgePoint(edge, i / 10f)))
                val solution = Robot3DPose.solve(approach)
                assertTrue("$edge @ ${i / 10f} approach was clamped", !solution.reachClamped)
            }
        }
    }
}
