package com.leumas.robot.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KinematicsTest {

    private val l1 = 240f
    private val l2 = 210f
    private val forearm = 110f
    private val shoulder = Vec2(500f, 400f)

    private fun solve(target: Vec2, facing: Float = 1f) =
        Kinematics.solve(shoulder, target, l1, l2, forearm, facing)

    @Test
    fun `reaches a target well inside the workspace`() {
        val target = Vec2(760f, 330f)
        val s = solve(target)
        assertFalse(s.reachClamped)
        assertTrue("gripper missed by ${s.gripper.distanceTo(target)}", s.gripper.distanceTo(target) < 1f)
    }

    @Test
    fun `the parallelogram holds the forearm horizontal in every pose`() {
        // This is the palletiser's signature constraint: wrist and gripper share a y for all poses.
        for (tx in 200..900 step 50) {
            for (ty in 60..600 step 45) {
                val s = solve(Vec2(tx.toFloat(), ty.toFloat()))
                assertEquals(
                    "forearm tilted at target ($tx,$ty)",
                    s.wrist.y.toDouble(), s.gripper.y.toDouble(), 0.01,
                )
            }
        }
    }

    @Test
    fun `forearm points the way the machine faces`() {
        val right = solve(Vec2(800f, 300f), facing = 1f)
        assertTrue(right.gripper.x > right.wrist.x)
        val left = solve(Vec2(200f, 300f), facing = -1f)
        assertTrue(left.gripper.x < left.wrist.x)
    }

    @Test
    fun `link lengths are preserved exactly`() {
        for (tx in 150..950 step 100) {
            val s = solve(Vec2(tx.toFloat(), 250f))
            assertEquals("lower arm", l1.toDouble(), s.shoulder.distanceTo(s.elbow).toDouble(), 0.05)
            assertEquals("upper arm", l2.toDouble(), s.elbow.distanceTo(s.wrist).toDouble(), 0.05)
            assertEquals("forearm", forearm.toDouble(), s.wrist.distanceTo(s.gripper).toDouble(), 0.05)
        }
    }

    @Test
    fun `elbow up - the elbow sits above the shoulder-wrist line`() {
        val s = solve(Vec2(820f, 400f))
        // y grows downward, so "above" means a smaller y than the chord midpoint.
        val chordMidY = (s.shoulder.y + s.wrist.y) / 2f
        assertTrue("elbow at ${s.elbow.y} vs chord $chordMidY", s.elbow.y < chordMidY)
    }

    @Test
    fun `an unreachable target clamps to full extension instead of breaking`() {
        // An off-screen module far beyond arm span: the arm should strain toward it, fully extended.
        val target = Vec2(3000f, 400f)
        val s = solve(target)
        assertTrue(s.reachClamped)
        val span = s.shoulder.distanceTo(s.wrist)
        assertEquals("arm should be near full extension", (l1 + l2).toDouble(), span.toDouble(), 1.0)
    }

    @Test
    fun `a target on top of the shoulder does not produce NaN`() {
        val s = solve(shoulder)
        assertTrue(s.elbow.x.isFinite() && s.elbow.y.isFinite())
        assertTrue(s.wrist.x.isFinite() && s.wrist.y.isFinite())
        assertTrue(s.gripper.x.isFinite() && s.gripper.y.isFinite())
    }

    @Test
    fun `angles stay finite across a full sweep`() {
        for (deg in 0 until 360 step 5) {
            val r = 300f
            val rad = Math.toRadians(deg.toDouble())
            val t = Vec2(
                shoulder.x + (r * kotlin.math.cos(rad)).toFloat(),
                shoulder.y + (r * kotlin.math.sin(rad)).toFloat(),
            )
            val s = solve(t)
            assertTrue("shoulder angle NaN at $deg", s.shoulderAngle.isFinite())
            assertTrue("elbow angle NaN at $deg", s.elbowInteriorAngle.isFinite())
            assertTrue(
                "elbow angle out of range at $deg: ${s.elbowInteriorAngle}",
                s.elbowInteriorAngle >= 0f && s.elbowInteriorAngle <= Math.PI.toFloat() + 1e-3f,
            )
        }
    }

    @Test
    fun `the wrist lands on its target from every direction and distance`() {
        // Regression: both IK branches must put the wrist ON the target. Applying the same elbow
        // bend direction to the mirrored branch left the elbow correct but the wrist far away,
        // which showed up as left-edge and bottom-edge modules being silently unreachable.
        var worst = 0.0
        var worstAt = ""
        for (deg in 0 until 360 step 3) {
            for (r in listOf(40f, 120f, 200f, 300f, 400f, 448f)) {
                val rad = Math.toRadians(deg.toDouble())
                val target = Vec2(
                    shoulder.x + (r * kotlin.math.cos(rad)).toFloat(),
                    shoulder.y + (r * kotlin.math.sin(rad)).toFloat(),
                )
                for (facing in listOf(-1f, 1f)) {
                    val s = solve(target, facing)
                    if (s.reachClamped) continue
                    val err = s.gripper.distanceTo(target).toDouble()
                    if (err > worst) {
                        worst = err
                        worstAt = "deg=$deg r=$r facing=$facing"
                    }
                }
            }
        }
        assertTrue("worst gripper error was $worst at $worstAt", worst < 0.5)
    }

    @Test
    fun `yaw squash sweeps through edge-on at ninety degrees`() {
        assertEquals(1.0, Kinematics.yawSquash(0f).toDouble(), 1e-4)
        assertTrue(abs(Kinematics.yawSquash(Math.PI.toFloat() / 2f)) < 1e-4)
        assertEquals(-1.0, Kinematics.yawSquash(Math.PI.toFloat()).toDouble(), 1e-4)
    }
}
