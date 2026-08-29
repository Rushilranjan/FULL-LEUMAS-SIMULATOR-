package com.leumas.robot.render3d

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.leumas.robot.render.Kinematics
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the articulated 4-axis palletiser in real 3-D (post-demo change 10.3).
 *
 * The scene is procedural: every part is an instance of a unit box or cylinder placed with a model
 * matrix, so there are no model assets and no extra dependencies. Joint positions come from the IK
 * solve in [Robot3DPose], lifted into world space by the base yaw and carriage position, so the
 * shoulder and elbow bend and the turret rotates as genuine 3-D motion.
 *
 * The renderer is a pure consumer: [poseProvider] hands it the latest pose each frame (computed on
 * the UI/frame thread from the shared animation state). It never drives or predicts anything.
 */
class SceneRenderer(
    private val poseProvider: () -> Robot3DPose?,
) : GLSurfaceView.Renderer {

    private lateinit var program: GlProgram
    private lateinit var box: Mesh
    private lateinit var cyl: Mesh

    private var aspect: Float = 1.7f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val scratch = FloatArray(16)

    private val eye = floatArrayOf(0.0f, 1.35f, 3.1f)
    private val lightDir = normalize(floatArrayOf(-0.35f, -1.0f, -0.45f))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.039f, 0.055f, 0.070f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // Culling is left off: the procedural meshes aren't guaranteed a consistent winding, and the
        // scene is tiny, so depth testing alone keeps it correct without risking dropped faces.
        program = GlProgram()
        box = Mesh.box()
        cyl = Mesh.cylinder(28)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1.7f else width.toFloat() / height.toFloat()
        perspective(proj, 42f, aspect, 0.05f, 30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 0.55f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        program.use()
        program.setCamera(viewProj, eye, lightDir)

        drawStaticScene()

        val pose = poseProvider() ?: Robot3DPose.idle()
        drawRail(pose.carriageX)
        drawRobot(pose)
    }

    // -- Static ground -------------------------------------------------------------------------

    private fun drawStaticScene() {
        // Floor slab, well below and around the machine.
        modelBox(0f, -0.03f, 0.2f, 9f, 0.04f, 7f, yawDeg = 0f)
        program.setModel(model, 0.055f, 0.065f, 0.080f, specular = 0.04f, shininess = 8f)
        box.draw(program)
    }

    // -- Rail ----------------------------------------------------------------------------------

    private fun drawRail(carriageX: Float) {
        val span = Arena3D.RAIL_HALF * 2f + 0.5f
        // Rail body.
        modelBox(0f, 0.0f, 0f, span, 0.07f, 0.22f)
        program.setModel(model, 0.20f, 0.22f, 0.25f, specular = 0.12f, shininess = 16f)
        box.draw(program)

        // Lead screw: a long thin cylinder lying along X.
        cylinderBetween(-span / 2f, 0.02f, 0f, span / 2f, 0.02f, 0f, radius = 0.028f)
        program.setModel(model, 0.72f, 0.75f, 0.78f, specular = 0.6f, shininess = 48f)
        cyl.draw(program)

        // End supports.
        listOf(-1f, 1f).forEach { s ->
            modelBox(s * span / 2f, 0.03f, 0f, 0.10f, 0.20f, 0.30f)
            program.setModel(model, 0.13f, 0.14f, 0.16f, specular = 0.1f, shininess = 12f)
            box.draw(program)
        }

        // Carriage riding the rail.
        modelBox(carriageX, 0.05f, 0f, 0.34f, 0.12f, 0.30f)
        program.setModel(model, 0.26f, 0.29f, 0.33f, specular = 0.2f, shininess = 20f)
        box.draw(program)
        // Mounting plate on top of the carriage.
        modelBox(carriageX, 0.12f, 0f, 0.40f, 0.03f, 0.34f)
        program.setModel(model, 0.18f, 0.20f, 0.23f, specular = 0.2f, shininess = 20f)
        box.draw(program)
    }

    // -- Robot ---------------------------------------------------------------------------------

    private fun drawRobot(pose: Robot3DPose) {
        val cx = pose.carriageX
        val yawDeg = Math.toDegrees(pose.baseYaw.toDouble()).toFloat()

        // Base pedestal (fixed, rides the carriage).
        modelBox(cx, 0.20f, 0f, 0.32f, 0.16f, 0.30f)
        program.setModel(model, 0.17f, 0.19f, 0.22f, specular = 0.15f, shininess = 14f)
        box.draw(program)
        modelBox(cx, 0.36f, 0f, 0.26f, 0.16f, 0.24f)
        program.setModel(model, 0.20f, 0.22f, 0.25f, specular = 0.15f, shininess = 14f)
        box.draw(program)

        // Slewing bearing ring (white), the A1 axis — a flat disc.
        cylinderBetween(cx, 0.44f, 0f, cx, 0.47f, 0f, radius = 0.15f)
        program.setModel(model, 0.85f, 0.86f, 0.88f, specular = 0.5f, shininess = 40f)
        cyl.draw(program)

        // Everything above the ring yaws with A1.
        // Turret casting (red).
        modelBox(cx, Arena3D.SHOULDER_Y - 0.06f, 0f, 0.24f, 0.22f, 0.22f, yawDeg = yawDeg)
        program.setModel(model, 0.77f, 0.12f, 0.14f, specular = 0.35f, shininess = 28f)
        box.draw(program)

        // Counterweight drum, cantilevered behind the turret (in the yawed -forward direction).
        val fwd = forward(pose.baseYaw)
        val drumX = cx - fwd[0] * 0.26f
        val drumZ = -fwd[2] * 0.26f
        cylinderBetween(
            drumX - fwd[0] * 0.06f, Arena3D.SHOULDER_Y - 0.02f, drumZ - fwd[2] * 0.06f,
            drumX + fwd[0] * 0.06f, Arena3D.SHOULDER_Y - 0.02f, drumZ + fwd[2] * 0.06f,
            radius = 0.11f,
        )
        program.setModel(model, 0.06f, 0.06f, 0.06f, specular = 0.3f, shininess = 30f)
        cyl.draw(program)

        // Arm joints in world space, from the planar IK solution.
        val arm: Kinematics.ArmSolution = pose.arm
        val shoulder = worldOf(arm.shoulder.x, arm.shoulder.y, cx, pose.baseYaw)
        val elbow = worldOf(arm.elbow.x, arm.elbow.y, cx, pose.baseYaw)
        val wrist = worldOf(arm.wrist.x, arm.wrist.y, cx, pose.baseYaw)
        val gripper = worldOf(arm.gripper.x, arm.gripper.y, cx, pose.baseYaw)

        // Lower arm (shoulder -> elbow), painted red, thick.
        cylinderBetween3(shoulder, elbow, radius = 0.075f)
        program.setModel(model, 0.80f, 0.14f, 0.16f, specular = 0.3f, shininess = 26f)
        cyl.draw(program)
        // Upper arm (elbow -> wrist).
        cylinderBetween3(elbow, wrist, radius = 0.060f)
        program.setModel(model, 0.80f, 0.14f, 0.16f, specular = 0.3f, shininess = 26f)
        cyl.draw(program)
        // Forearm (wrist -> gripper), held horizontal by the parallelogram.
        cylinderBetween3(wrist, gripper, radius = 0.048f)
        program.setModel(model, 0.82f, 0.16f, 0.18f, specular = 0.3f, shininess = 26f)
        cyl.draw(program)

        // Chrome parallelogram tie-rod, running above the lower arm.
        val rodA = floatArrayOf(shoulder[0], shoulder[1] + 0.10f, shoulder[2])
        val rodB = floatArrayOf(elbow[0], elbow[1] + 0.08f, elbow[2])
        cylinderBetween3(rodA, rodB, radius = 0.018f)
        program.setModel(model, 0.80f, 0.82f, 0.85f, specular = 0.7f, shininess = 56f)
        cyl.draw(program)

        // Joint housings (dark stubby cylinders across each pivot).
        drawJoint(shoulder, pose.baseYaw, 0.085f)
        drawJoint(elbow, pose.baseYaw, 0.070f)
        drawJoint(wrist, pose.baseYaw, 0.055f)

        drawEndEffector(pose, wrist, gripper)
    }

    private fun drawJoint(p: FloatArray, yaw: Float, radius: Float) {
        // A short cylinder whose axis is the pivot line: perpendicular to the reach plane, i.e. the
        // world "side" direction for this yaw.
        val side = sideAxis(yaw)
        cylinderBetween(
            p[0] - side[0] * radius, p[1], p[2] - side[2] * radius,
            p[0] + side[0] * radius, p[1], p[2] + side[2] * radius,
            radius = radius,
        )
        program.setModel(model, 0.11f, 0.11f, 0.12f, specular = 0.4f, shininess = 34f)
        cyl.draw(program)
    }

    private fun drawEndEffector(pose: Robot3DPose, wrist: FloatArray, gripper: FloatArray) {
        val fdir = normalize(sub(gripper, wrist))
        // Side axis in the horizontal plane, rolled about the forearm by the wrist (A4). When the
        // forearm is near-vertical (a TOP-edge reach) fdir ≈ UP and cross(fdir, UP) collapses; fall
        // back to the turret's own side axis (change 11.3) so the fingers stay aligned with the base
        // instead of snapping to a fixed world axis as the forearm crosses vertical.
        var side = normalize(cross(fdir, UP))
        if (len(side) < 1e-3f) side = sideAxis(pose.baseYaw)
        side = rotateAbout(side, fdir, pose.wristRoll)

        // Wrist actuator body.
        modelAt(gripper, 0.10f, 0.10f, 0.10f)
        program.setModel(model, 0.15f, 0.15f, 0.16f, specular = 0.35f, shininess = 30f)
        box.draw(program)

        // Two fingers, spreading with gripperOpen, reaching a little past the gripper.
        val spread = 0.03f + 0.05f * pose.gripperOpen
        val tip = add(gripper, scale(fdir, 0.10f))
        listOf(-1f, 1f).forEach { s ->
            val fingerPos = add(tip, scale(side, s * spread))
            cylinderBetween3(add(fingerPos, scale(fdir, -0.02f)), add(fingerPos, scale(fdir, 0.08f)), radius = 0.018f)
            program.setModel(model, 0.16f, 0.16f, 0.17f, specular = 0.3f, shininess = 24f)
            cyl.draw(program)
        }

        if (pose.carrying) {
            val boxCentre = add(gripper, scale(fdir, 0.14f))
            modelAt(boxCentre, 0.14f, 0.12f, 0.14f)
            program.setModel(model, 0.85f, 0.72f, 0.42f, specular = 0.15f, shininess = 12f)
            box.draw(program)
        }
    }

    // -- World mapping -------------------------------------------------------------------------

    /** Lift a reach-plane point (u along facing, planeY screen-style) into world space. */
    private fun worldOf(u: Float, planeY: Float, carriageX: Float, yaw: Float): FloatArray {
        val f = forward(yaw)
        return floatArrayOf(
            carriageX + u * f[0],
            Arena3D.SHOULDER_Y - planeY,
            u * f[2],
        )
    }

    private fun forward(yaw: Float) = floatArrayOf(sin(yaw), 0f, cos(yaw))
    private fun sideAxis(yaw: Float) = normalize(cross(forward(yaw), UP))

    // -- Matrix builders -----------------------------------------------------------------------

    private fun modelBox(px: Float, py: Float, pz: Float, sx: Float, sy: Float, sz: Float, yawDeg: Float = 0f) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, px, py, pz)
        if (yawDeg != 0f) Matrix.rotateM(model, 0, yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
    }

    private fun modelAt(p: FloatArray, sx: Float, sy: Float, sz: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, p[0], p[1], p[2])
        Matrix.scaleM(model, 0, sx, sy, sz)
    }

    private fun cylinderBetween(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float) {
        cylinderBetween3(floatArrayOf(ax, ay, az), floatArrayOf(bx, by, bz), radius)
    }

    /** Orient the unit cylinder (axis +Y) so it spans A..B with the given radius. */
    private fun cylinderBetween3(a: FloatArray, b: FloatArray, radius: Float) {
        val dir = sub(b, a)
        val length = len(dir).coerceAtLeast(1e-4f)
        val mid = floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, mid[0], mid[1], mid[2])
        alignYTo(scale(dir, 1f / length))
        Matrix.multiplyMM(scratch, 0, model, 0, rot, 0)
        System.arraycopy(scratch, 0, model, 0, 16)
        Matrix.scaleM(model, 0, radius * 2f, length, radius * 2f)
    }

    private val rot = FloatArray(16)

    /** Build [rot] as the rotation taking +Y to unit vector [d]. */
    private fun alignYTo(d: FloatArray) {
        Matrix.setIdentityM(rot, 0)
        val dot = d[1].coerceIn(-1f, 1f)
        if (dot > 0.9999f) return
        if (dot < -0.9999f) {
            Matrix.setRotateM(rot, 0, 180f, 1f, 0f, 0f)
            return
        }
        val axis = normalize(cross(UP, d))
        val angle = Math.toDegrees(acos(dot).toDouble()).toFloat()
        Matrix.setRotateM(rot, 0, angle, axis[0], axis[1], axis[2])
    }

    // -- vec helpers ---------------------------------------------------------------------------

    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun len(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    /** Rodrigues rotation of [v] about unit [axis] by [angle] radians. */
    private fun rotateAbout(v: FloatArray, axis: FloatArray, angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        val dot = axis[0] * v[0] + axis[1] * v[1] + axis[2] * v[2]
        val cr = cross(axis, v)
        return floatArrayOf(
            v[0] * c + cr[0] * s + axis[0] * dot * (1 - c),
            v[1] * c + cr[1] * s + axis[1] * dot * (1 - c),
            v[2] * c + cr[2] * s + axis[2] * dot * (1 - c),
        )
    }

    private companion object {
        val UP = floatArrayOf(0f, 1f, 0f)

        fun normalize(v: FloatArray): FloatArray {
            val l = hypot(hypot(v[0], v[1]), v[2])
            return if (l < 1e-6f) v else floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }

        /** Right-handed perspective, matching gluPerspective. */
        fun perspective(out: FloatArray, fovyDeg: Float, aspect: Float, near: Float, far: Float) {
            val f = (1.0 / kotlin.math.tan(Math.toRadians(fovyDeg / 2.0))).toFloat()
            java.util.Arrays.fill(out, 0f)
            out[0] = f / aspect
            out[5] = f
            out[10] = (far + near) / (near - far)
            out[11] = -1f
            out[14] = (2f * far * near) / (near - far)
        }
    }
}
