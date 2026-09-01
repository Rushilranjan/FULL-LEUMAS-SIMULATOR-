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
 * The scene is procedural: every part is an instance of a unit box, cylinder or screw thread placed
 * with a model matrix, so there are no model assets and no extra dependencies. Joint positions come
 * from the IK solve in [Robot3DPose], lifted into world space by the base yaw and carriage position,
 * so the shoulder and elbow bend and the turret rotates as genuine 3-D motion.
 *
 * The renderer is a pure consumer: [poseProvider] hands it the latest pose each frame (computed on
 * the UI/frame thread from the shared animation state). It never drives or predicts anything. The
 * only state it owns is a wall clock, used purely to animate decorative material particles.
 */
class SceneRenderer(
    private val poseProvider: () -> Robot3DPose?,
) : GLSurfaceView.Renderer {

    private lateinit var program: GlProgram
    private lateinit var box: Mesh
    private lateinit var cyl: Mesh
    private lateinit var thread: Mesh
    private lateinit var grain: Mesh
    private lateinit var pail: Mesh
    private lateinit var heap: Mesh
    private lateinit var hopper: Mesh

    private var aspect: Float = 1.7f
    private val startNanos = System.nanoTime()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val scratch = FloatArray(16)

    private val eye = floatArrayOf(0.0f, 1.35f, 3.1f)
    private val lightDir = normalize(floatArrayOf(-0.35f, -1.0f, -0.45f))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Change 14.1: the backdrop is now a single flat tone and the clear colour *is* the whole
        // backdrop — there is no wall or floor geometry left to draw.
        GLES30.glClearColor(BACKDROP[0], BACKDROP[1], BACKDROP[2], 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // Change 13.8: the liquid in the bucket and the pour stream are translucent.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // Culling is left off: the procedural meshes aren't guaranteed a consistent winding, and the
        // scene is tiny, so depth testing alone keeps it correct without risking dropped faces.
        program = GlProgram()
        box = Mesh.box()
        cyl = Mesh.cylinder(28)
        thread = Mesh.screwThread(turns = SCREW_TURNS)
        grain = Mesh.granule()
        // A real pail tapers in toward the base; the heap is a shallow mound of powder.
        pail = Mesh.frustum(topRadius = 0.50f, bottomRadius = 0.38f, segments = 26)
        heap = Mesh.frustum(topRadius = 0.18f, bottomRadius = 0.50f, segments = 20)
        // Hopper cone: wide at the top, tapering to the dispenser inlet (change 15.1).
        hopper = Mesh.frustum(topRadius = 0.50f, bottomRadius = 0.15f, segments = 24)
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

        val timeS = (System.nanoTime() - startNanos) / 1_000_000_000f

        drawContactShadow()

        val pose = poseProvider() ?: Robot3DPose.idle()
        drawRail(pose.carriageX)
        drawRobot(pose, timeS)
    }

    // -- Ground shadow ---------------------------------------------------------------------------

    /**
     * A soft contact shadow under the machine (change 14.1).
     *
     * There is no floor any more. The wall, floor slab and near apron were three big boxes that left
     * a hard horizon seam across the arena, and the floor was opaque and reached past the camera —
     * which is what the arm was disappearing behind whenever it served a bottom-edge module
     * (change 14.2). Deleting them made the field perfectly even and fixed the occlusion in one go.
     *
     * All that replaces them is this: a few concentric flattened discs, fading outward, so the
     * machine sits on the grey instead of floating on it. Drawn first, translucent, with depth
     * writes off, so it can never occlude anything drawn afterwards — including an arm reaching down
     * in front of it.
     */
    private fun drawContactShadow() {
        GLES30.glDepthMask(false)
        // Drawn largest-first with a low alpha each, so the discs accumulate into a smooth gradient.
        // Few, darker discs produce visible concentric steps — it read as a target painted on the
        // floor on the first device run.
        for (i in SHADOW_RINGS - 1 downTo 0) {
            val t = i.toFloat() / (SHADOW_RINGS - 1)
            val radius = SHADOW_INNER + (SHADOW_OUTER - SHADOW_INNER) * t
            val alpha = SHADOW_ALPHA * (1f - t)
            // A very flat cylinder is a disc, squashed hard in Z so the shadow hugs the rail rather
            // than spreading as a big circle on a floor that no longer exists.
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, SHADOW_Y, SHADOW_Z)
            Matrix.scaleM(model, 0, radius * 2f, 0.001f, radius * SHADOW_SQUASH)
            program.setModel(model, 0.42f, 0.45f, 0.49f, specular = 0f, shininess = 4f, alpha = alpha)
            cyl.draw(program)
        }
        GLES30.glDepthMask(true)
    }

    // -- Rail ------------------------------------------------------------------------------------

    /**
     * The screw-driven linear axis (change 13.5).
     *
     * Built as a real mechanism rather than a bar with a cylinder on it: a T-slot extrusion, two
     * round guide shafts, a ball screw with an actual helical thread, pillow blocks at both ends, a
     * ball-nut on the carriage and a cable drag chain. The thread mesh is rotated about its own axis
     * in proportion to carriage travel, so the screw visibly *drives* the carriage — turn one way
     * and the machine runs right, the other way and it runs left.
     */
    private fun drawRail(carriageX: Float) {
        val span = Arena3D.RAIL_HALF * 2f + 0.5f
        val half = span / 2f

        // Extrusion body, with two inset side grooves to read as a T-slot profile.
        modelBox(0f, -0.005f, 0f, span, 0.10f, 0.30f)
        program.setModel(model, 0.62f, 0.65f, 0.69f, specular = 0.22f, shininess = 26f)
        box.draw(program)
        listOf(-1f, 1f).forEach { s ->
            modelBox(0f, -0.005f, s * 0.152f, span * 0.995f, 0.036f, 0.02f)
            program.setModel(model, 0.34f, 0.37f, 0.41f, specular = 0.15f, shininess = 18f)
            box.draw(program)
        }

        // Round guide shafts either side of the screw.
        listOf(-1f, 1f).forEach { s ->
            cylinderBetween(-half, 0.075f, s * 0.105f, half, 0.075f, s * 0.105f, radius = 0.026f)
            program.setModel(model, 0.80f, 0.83f, 0.86f, specular = 0.75f, shininess = 60f)
            cyl.draw(program)
        }

        // Ball screw: a polished core plus the helical thread riding on it. The thread's spin is a
        // pure function of carriage position, so it can never drift out of step with the motion.
        cylinderBetween(-half, 0.075f, 0f, half, 0.075f, 0f, radius = 0.021f)
        program.setModel(model, 0.72f, 0.75f, 0.78f, specular = 0.6f, shininess = 48f)
        cyl.draw(program)

        // One revolution must advance the carriage by exactly one thread pitch, or the thread
        // visibly creeps against the motion instead of driving it.
        val lead = span / SCREW_TURNS
        val spinDeg = carriageX / lead * 360f
        screwModel(0f, 0.075f, 0f, span, radius = 0.036f, spinDeg = spinDeg)
        program.setModel(model, 0.76f, 0.79f, 0.82f, specular = 0.7f, shininess = 54f)
        thread.draw(program)

        // Pillow blocks / screw supports at both ends.
        listOf(-1f, 1f).forEach { s ->
            modelBox(s * half, 0.09f, 0f, 0.11f, 0.26f, 0.34f)
            program.setModel(model, 0.28f, 0.31f, 0.35f, specular = 0.18f, shininess = 20f)
            box.draw(program)
            cylinderBetween(
                s * half - 0.035f, 0.075f, 0f, s * half + 0.035f, 0.075f, 0f, radius = 0.055f,
            )
            program.setModel(model, 0.16f, 0.17f, 0.19f, specular = 0.4f, shininess = 34f)
            cyl.draw(program)
        }

        drawCableChain(carriageX, half)

        // Carriage riding the rail.
        modelBox(carriageX, 0.055f, 0f, 0.34f, 0.13f, 0.32f)
        program.setModel(model, 0.30f, 0.33f, 0.37f, specular = 0.24f, shininess = 24f)
        box.draw(program)
        // Ball-nut wrapped around the screw.
        cylinderBetween(
            carriageX - 0.075f, 0.075f, 0f, carriageX + 0.075f, 0.075f, 0f, radius = 0.052f,
        )
        program.setModel(model, 0.18f, 0.20f, 0.23f, specular = 0.45f, shininess = 40f)
        cyl.draw(program)
        // Linear bearings on the guide shafts.
        listOf(-1f, 1f).forEach { s ->
            cylinderBetween(
                carriageX - 0.06f, 0.075f, s * 0.105f, carriageX + 0.06f, 0.075f, s * 0.105f,
                radius = 0.042f,
            )
            program.setModel(model, 0.20f, 0.22f, 0.25f, specular = 0.4f, shininess = 34f)
            cyl.draw(program)
        }
        // Mounting plate on top of the carriage.
        modelBox(carriageX, 0.128f, 0f, 0.40f, 0.03f, 0.36f)
        program.setModel(model, 0.22f, 0.24f, 0.28f, specular = 0.25f, shininess = 22f)
        box.draw(program)
    }

    /** Energy chain trailing the carriage back to the left-hand end of the axis. */
    private fun drawCableChain(carriageX: Float, half: Float) {
        val anchorX = -half + 0.06f
        val z = -0.20f
        for (i in 0 until CHAIN_LINKS) {
            val t = i.toFloat() / (CHAIN_LINKS - 1)
            val x = anchorX + (carriageX - anchorX) * t
            // Slight sag toward the middle of the run.
            val sag = 0.018f * sin(Math.PI.toFloat() * t)
            modelBox(x, 0.045f - sag, z, 0.05f, 0.035f, 0.055f)
            program.setModel(model, 0.14f, 0.15f, 0.17f, specular = 0.12f, shininess = 14f)
            box.draw(program)
        }
    }

    // -- Robot -----------------------------------------------------------------------------------

    private fun drawRobot(pose: Robot3DPose, timeS: Float) {
        val cx = pose.carriageX
        val yawDeg = Math.toDegrees(pose.baseYaw.toDouble()).toFloat()

        // Base pedestal (fixed, rides the carriage).
        modelBox(cx, 0.22f, 0f, 0.32f, 0.16f, 0.30f)
        program.setModel(model, 0.19f, 0.21f, 0.24f, specular = 0.15f, shininess = 14f)
        box.draw(program)
        modelBox(cx, 0.37f, 0f, 0.26f, 0.16f, 0.24f)
        program.setModel(model, 0.22f, 0.24f, 0.27f, specular = 0.15f, shininess = 14f)
        box.draw(program)

        // Slewing bearing ring (white), the A1 axis — a flat disc.
        cylinderBetween(cx, 0.44f, 0f, cx, 0.47f, 0f, radius = 0.15f)
        program.setModel(model, 0.88f, 0.89f, 0.91f, specular = 0.5f, shininess = 40f)
        cyl.draw(program)

        // Everything above the ring yaws with A1.
        // Turret casting (red).
        modelBox(cx, Arena3D.SHOULDER_Y - 0.06f, 0f, 0.24f, 0.22f, 0.22f, yawDeg = yawDeg)
        program.setModel(model, 0.77f, 0.12f, 0.14f, specular = 0.35f, shininess = 28f)
        box.draw(program)

        // Counterweight drum, cantilevered behind the turret (in the yawed -forward direction).
        //
        // Change 14.7: this used to sit 0.26 back with a 0.06 half-length, while the turret casting
        // is only 0.22 deep — so its near face was at 0.20 against a turret back face at 0.12, an
        // 0.08-unit gap with nothing spanning it. It read exactly as the user described: a black
        // disc levitating behind the robot. It is pulled in to overlap the casting and a boss now
        // bridges the two. Both are placed from the same `fwd`, so they stay joined at every yaw.
        val fwd = forward(pose.baseYaw)
        val drumCentre = DRUM_OFFSET
        val drumX = cx - fwd[0] * drumCentre
        val drumZ = -fwd[2] * drumCentre
        val drumY = Arena3D.SHOULDER_Y - 0.02f

        // Boss joining the turret casting to the drum.
        cylinderBetween(
            cx - fwd[0] * 0.05f, drumY, -fwd[2] * 0.05f,
            drumX, drumY, drumZ,
            radius = 0.055f,
        )
        program.setModel(model, 0.14f, 0.14f, 0.15f, specular = 0.3f, shininess = 26f)
        cyl.draw(program)

        cylinderBetween(
            drumX - fwd[0] * 0.06f, drumY, drumZ - fwd[2] * 0.06f,
            drumX + fwd[0] * 0.06f, drumY, drumZ + fwd[2] * 0.06f,
            radius = 0.11f,
        )
        program.setModel(model, 0.08f, 0.08f, 0.08f, specular = 0.3f, shininess = 30f)
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
        program.setModel(model, 0.78f, 0.80f, 0.83f, specular = 0.7f, shininess = 56f)
        cyl.draw(program)

        // Joint housings (dark stubby cylinders across each pivot).
        drawJoint(shoulder, pose.baseYaw, 0.085f)
        drawJoint(elbow, pose.baseYaw, 0.070f)
        drawJoint(wrist, pose.baseYaw, 0.055f)

        drawEndEffector(pose, wrist, gripper, timeS)
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
        program.setModel(model, 0.13f, 0.13f, 0.14f, specular = 0.4f, shininess = 34f)
        cyl.draw(program)
    }

    private fun drawEndEffector(pose: Robot3DPose, wrist: FloatArray, gripper: FloatArray, timeS: Float) {
        val fdir = normalize(sub(gripper, wrist))

        // Side axis in the horizontal plane, rolled about the forearm by the wrist (A4).
        //
        // Change 13.3: when the forearm approaches vertical, cross(fdir, UP) collapses and its
        // direction becomes meaningless. This used to switch hard to the turret's side axis below a
        // threshold, which snapped the fingers every time a top-edge reach crossed vertical. Now the
        // two candidates are blended across a band, so the hand rotates through the singularity
        // instead of jumping across it.
        val rawSide = cross(fdir, UP)
        val fallback = sideAxis(pose.baseYaw)
        val confidence = smoothstep((len(rawSide) - SIDE_BLEND_LO) / (SIDE_BLEND_HI - SIDE_BLEND_LO))
        var side = normalize(
            add(scale(normalize(rawSide), confidence), scale(fallback, 1f - confidence))
        )
        if (len(side) < 1e-3f) side = fallback
        side = rotateAbout(side, fdir, pose.wristRoll)

        drawVessel(pose, gripper, side, timeS)
    }

    // -- Vessel and dispensers (change 15.1) -----------------------------------------------------

    /**
     * The process vessel on the end effector, its dispenser, and the material stream.
     *
     * **Change 15.1 replaced the bucket.** A pail that tipped to pour was the wrong machine: the rig
     * doses measured quantities, and you cannot tip a bucket accurately. The tool is now a small
     * process vessel bolted rigidly to the wrist — no bail, no yoke, no swing — with a conical hopper
     * and a powered dispenser underneath. Because it is fed by a pump it never tips, which is also
     * why [Payload.tilt] is gone.
     *
     * **Change 16.6 finished the job.** 15.1 kept two bracket rods between the wrist and the vessel
     * flange, and with daylight between them they still read as a carry handle — the bucket the
     * change was supposed to abolish, hung from a yoke. The mount is now a single continuous body:
     * actuator, bearing race, tapered neck, flange, all on one axis with no gap anywhere. The head
     * is not carried by the arm, it is part of it, which is how a real dosing end effector is
     * built and the only way it reads as one from every angle the turret swings through.
     *
     * The dispenser is chosen by material, because one mechanism cannot honestly do both:
     *
     *  - **Liquid** gets a true peristaltic head — a circular race, a three-roller rotor, and
     *    flexible tubing squeezed between them. This is the standard way to dose a liquid without
     *    the pumped fluid ever touching the pump.
     *  - **Solid** gets a screw auger under the hopper cone. A peristaltic head physically cannot
     *    move dry powder; an auger is what a real powder-dosing skid uses.
     *
     * Both are driven by [Payload.pumpAngle], which only advances while material is actually
     * flowing, so the mechanism is visibly doing the work rather than free-running.
     */
    private fun drawVessel(
        pose: Robot3DPose,
        gripper: FloatArray,
        side: FloatArray,
        timeS: Float,
    ) {
        val payload = pose.payload
        // The vessel hangs plumb. It is rigidly mounted, so it does not swing with the wrist and it
        // never tips — the axis is world-up regardless of arm pose.
        val axis = UP
        val down = floatArrayOf(0f, -1f, 0f)
        val out = normalize(cross(axis, side))

        val top = add(gripper, scale(down, VESSEL_DROP))
        val bottom = add(top, scale(down, VESSEL_HEIGHT))
        val hopperTip = add(bottom, scale(down, HOPPER_HEIGHT))

        // -- mount: one continuous machined neck from the wrist into the vessel --------------------
        //
        // Change 16.6 removed the two bracket struts. They read as a carry handle -- a pail slung
        // under the tool on a pair of rods, with daylight between the two -- which is exactly the
        // bucket the vessel was meant to stop being. A dosing head on a real arm is not *carried*
        // by the wrist, it IS the wrist: one machined body, flange to flange, nothing hanging.
        //
        // So the mount is now solid and continuous. The wrist actuator seats a collar, the collar
        // tapers into the vessel flange, and every section shares the same axis with no gap
        // between them, so from any angle the tool reads as one part rather than an assembly.

        // Wrist actuator housing: the driven joint the whole head is keyed to.
        modelAt(gripper, 0.10f, 0.10f, 0.10f)
        program.setModel(model, 0.17f, 0.17f, 0.18f, specular = 0.35f, shininess = 30f)
        box.draw(program)

        // Bearing race under the actuator -- the surface the head rotates on.
        val raceTop = add(gripper, scale(down, 0.030f))
        val raceBottom = add(raceTop, scale(down, 0.016f))
        cylinderBetween3(raceTop, raceBottom, radius = NECK_RADIUS * 1.22f)
        program.setModel(model, 0.30f, 0.32f, 0.35f, specular = 0.75f, shininess = 70f, rim = 0.5f)
        cyl.draw(program)

        // The neck itself, tapering race -> flange. This is the piece that used to be two rods and
        // a gap; being a single frustum is the whole point of the change.
        frustumBetween(add(top, scale(axis, 0.004f)), raceBottom, radius = VESSEL_RADIUS * 1.12f)
        program.setModel(model, 0.26f, 0.28f, 0.31f, specular = 0.72f, shininess = 62f, rim = 0.6f)
        hopper.draw(program)

        // A pair of raised ribs up the neck. Detail rather than structure -- they make the taper
        // read as machined stock instead of a smooth cone, and they are on the body, so they add
        // nothing to lift and cannot be mistaken for a handle.
        listOf(-1f, 1f).forEach { sgn ->
            val ribOut = normalize(add(scale(side, sgn), scale(out, sgn * 0.35f)))
            val ribTop = add(add(raceBottom, scale(ribOut, NECK_RADIUS * 0.86f)), scale(down, 0.004f))
            val ribFoot = add(add(top, scale(ribOut, VESSEL_RADIUS * 0.94f)), scale(axis, 0.008f))
            cylinderBetween3(ribTop, ribFoot, radius = 0.0075f)
            program.setModel(model, 0.38f, 0.40f, 0.44f, specular = 0.85f, shininess = 76f, rim = 0.7f)
            cyl.draw(program)
        }

        // -- vessel shell ----------------------------------------------------------------------------
        //
        // The body is drawn *after* the contents, as glass, so the level is visible. A steel cylinder
        // hid its own contents completely: the fill and the heap were rendering correctly (see
        // DispenserTest) but were sealed inside an opaque tube, so on device the vessel looked empty
        // the whole way through the transfer. Sight glasses and clear polycarbonate dosing vessels
        // are standard for exactly this reason — an operator has to see the level.

        // Top flange ring.
        cylinderBetween3(
            add(top, scale(axis, -0.008f)), add(top, scale(axis, 0.010f)),
            radius = VESSEL_RADIUS * 1.12f,
        )
        program.setModel(model, 0.72f, 0.75f, 0.80f, specular = 0.9f, shininess = 80f, rim = 0.9f)
        cyl.draw(program)

        // Conical hopper, tapering to the dispenser inlet.
        frustumBetween(hopperTip, bottom, radius = VESSEL_RADIUS)
        program.setModel(model, STEEL[0] * 0.94f, STEEL[1] * 0.94f, STEEL[2] * 0.94f,
            specular = 0.8f, shininess = 66f, rim = 0.7f)
        hopper.draw(program)

        val solid = payload.kind == MaterialKind.SOLID
        val fill = payload.fill.coerceIn(0f, 1f)

        // -- contents ------------------------------------------------------------------------------

        if (fill > 0.02f) {
            val height = (VESSEL_HEIGHT - 0.022f) * fill
            val contentBottom = add(bottom, scale(axis, 0.010f))
            val contentTop = add(contentBottom, scale(axis, height))
            val contentRadius = VESSEL_RADIUS * 0.88f
            if (solid) {
                frustumBetween(contentBottom, contentTop, radius = contentRadius)
                program.setModel(model, POWDER[0], POWDER[1], POWDER[2], specular = 0.08f, shininess = 6f)
                heap.draw(program)
            } else {
                cylinderBetween3(contentBottom, contentTop, radius = contentRadius)
                drawTranslucent { alpha ->
                    program.setModel(
                        model, LIQUID[0], LIQUID[1], LIQUID[2],
                        specular = 0.6f, shininess = 60f, alpha = alpha,
                    )
                    cyl.draw(program)
                }
                if (payload.stream > 0.05f) {
                    drawRipples(contentTop, axis, contentRadius, timeS)
                }
            }
        }

        // Glass body over the contents.
        cylinderBetween3(bottom, top, radius = VESSEL_RADIUS)
        drawTranslucent { _ ->
            program.setModel(
                model, 0.86f, 0.91f, 0.96f,
                specular = 0.95f, shininess = 95f, alpha = GLASS_ALPHA, rim = 1.0f,
            )
            cyl.draw(program)
        }

        // Steel hoops banding the glass, which is what stops a clear tube reading as a plain
        // cylinder and gives the vessel its scale.
        listOf(0.30f, 0.70f).forEach { f ->
            val y = add(bottom, scale(axis, VESSEL_HEIGHT * f))
            cylinderBetween3(
                add(y, scale(axis, -0.005f)), add(y, scale(axis, 0.005f)),
                radius = VESSEL_RADIUS * 1.05f,
            )
            program.setModel(model, 0.48f, 0.51f, 0.56f, specular = 0.85f, shininess = 70f, rim = 0.7f)
            cyl.draw(program)
        }

        // -- dispenser -------------------------------------------------------------------------------

        val nozzle = if (solid) {
            drawAuger(hopperTip, out, side, axis, payload)
        } else {
            drawPeristalticHead(hopperTip, out, side, axis, payload)
        }

        if (payload.stream > 0.01f) {
            // Collection falls in through the open top; discharge leaves from the dispenser outlet.
            val origin = if (payload.pouring) nozzle else top
            drawStream(payload, origin, axis, side, timeS)
        }
    }

    /**
     * Screw auger under the hopper, for powder (change 15.1).
     *
     * A barrel running out from the hopper outlet with visible flighting turning inside it, a drive
     * motor on the inboard end, and a discharge spout at the tip. The flighting reuses
     * [Mesh.screwThread], which already exists for the rail lead screw — it is exactly the right
     * geometry, and an auger is what actually meters dry solids on a real dosing skid.
     *
     * Returns the spout tip, where the material leaves.
     */
    private fun drawAuger(
        inlet: FloatArray,
        out: FloatArray,
        side: FloatArray,
        axis: FloatArray,
        payload: Payload,
    ): FloatArray {
        val barrelStart = add(inlet, scale(out, -AUGER_BACK))
        val barrelEnd = add(inlet, scale(out, AUGER_LENGTH))

        // Barrel.
        cylinderBetween3(barrelStart, barrelEnd, radius = AUGER_RADIUS)
        program.setModel(model, 0.40f, 0.43f, 0.47f, specular = 0.7f, shininess = 58f, rim = 0.5f)
        cyl.draw(program)

        // Flighting, turning with the dispenser.
        spunBetween(barrelStart, barrelEnd, radius = AUGER_RADIUS * 0.82f, spinDeg = payload.pumpAngle)
        program.setModel(model, 0.78f, 0.80f, 0.84f, specular = 0.75f, shininess = 64f, rim = 0.6f)
        thread.draw(program)

        // Drive motor on the inboard end.
        val motorMid = add(barrelStart, scale(out, -0.032f))
        cylinderBetween3(add(motorMid, scale(out, 0.030f)), add(motorMid, scale(out, -0.030f)), radius = 0.040f)
        program.setModel(model, 0.13f, 0.13f, 0.14f, specular = 0.35f, shininess = 30f)
        cyl.draw(program)

        // Discharge spout, turned down at the tip.
        val spoutTop = add(barrelEnd, scale(out, -0.012f))
        val spoutTip = add(spoutTop, scale(axis, -AUGER_SPOUT))
        cylinderBetween3(spoutTop, spoutTip, radius = AUGER_RADIUS * 0.66f)
        program.setModel(model, 0.34f, 0.37f, 0.41f, specular = 0.7f, shininess = 56f, rim = 0.5f)
        cyl.draw(program)
        return spoutTip
    }

    /**
     * Peristaltic head under the hopper, for liquid (change 15.1).
     *
     * A circular race with a three-roller rotor squeezing a flexible tube against it. The tube runs
     * from the hopper outlet, wraps the race, and leaves at the nozzle; the rollers occlude it in
     * turn, which is what moves the liquid. The pumped fluid only ever touches the tube, which is
     * the reason this pump type is used for dosing in the first place.
     *
     * Returns the nozzle tip, where the material leaves.
     */
    private fun drawPeristalticHead(
        inlet: FloatArray,
        out: FloatArray,
        side: FloatArray,
        axis: FloatArray,
        payload: Payload,
    ): FloatArray {
        // The head sits just below the hopper outlet, its rotor face turned toward the camera.
        val hub = add(inlet, scale(axis, -PUMP_RADIUS * 0.85f))

        // Race housing: a short cylinder whose axis lies along the hand's side direction.
        cylinderBetween3(
            add(hub, scale(side, -PUMP_WIDTH / 2f)), add(hub, scale(side, PUMP_WIDTH / 2f)),
            radius = PUMP_RADIUS,
        )
        program.setModel(model, 0.30f, 0.33f, 0.38f, specular = 0.65f, shininess = 54f, rim = 0.5f)
        cyl.draw(program)

        // Rotor hub.
        cylinderBetween3(
            add(hub, scale(side, -PUMP_WIDTH * 0.62f)), add(hub, scale(side, PUMP_WIDTH * 0.62f)),
            radius = PUMP_RADIUS * 0.34f,
        )
        program.setModel(model, 0.16f, 0.16f, 0.18f, specular = 0.5f, shininess = 44f)
        cyl.draw(program)

        // Three rollers, 120 degrees apart, turning with the dispenser.
        val spin = Math.toRadians(payload.pumpAngle.toDouble()).toFloat()
        for (i in 0 until PUMP_ROLLERS) {
            val theta = spin + i * (2f * Math.PI.toFloat() / PUMP_ROLLERS)
            val offset = add(
                scale(out, cos(theta) * PUMP_ORBIT),
                scale(axis, sin(theta) * PUMP_ORBIT),
            )
            val centre = add(hub, offset)
            cylinderBetween3(
                add(centre, scale(side, -PUMP_WIDTH * 0.40f)),
                add(centre, scale(side, PUMP_WIDTH * 0.40f)),
                radius = PUMP_ROLLER_R,
            )
            program.setModel(model, 0.82f, 0.84f, 0.88f, specular = 0.85f, shininess = 76f, rim = 0.8f)
            cyl.draw(program)
        }

        // Flexible tube: down from the hopper outlet, around the outside of the race, out to the
        // nozzle. Swept as short segments so it reads as bent tubing rather than a rigid pipe.
        val tubeR = PUMP_ORBIT + PUMP_ROLLER_R * 0.9f
        var previous = inlet
        for (i in 0..PUMP_TUBE_SEGMENTS) {
            val t = i.toFloat() / PUMP_TUBE_SEGMENTS
            // Sweep from straight up, round the front of the race, to pointing down and outward.
            val theta = (0.5f + 1.15f * t) * Math.PI.toFloat()
            val point = add(
                hub,
                add(scale(out, cos(theta) * tubeR), scale(axis, sin(theta) * tubeR)),
            )
            cylinderBetween3(previous, point, radius = PUMP_TUBE_R)
            program.setModel(model, 0.86f, 0.88f, 0.90f, specular = 0.5f, shininess = 40f, alpha = 1f)
            cyl.draw(program)
            previous = point
        }

        // Nozzle at the tube exit.
        val nozzleTip = add(previous, scale(axis, -PUMP_NOZZLE))
        cylinderBetween3(previous, nozzleTip, radius = PUMP_TUBE_R * 1.35f)
        program.setModel(model, 0.34f, 0.37f, 0.41f, specular = 0.7f, shininess = 58f, rim = 0.5f)
        cyl.draw(program)
        return nozzleTip
    }

    /** Concentric rings spreading across the liquid surface where the stream lands (change 14.3). */
    private fun drawRipples(surface: FloatArray, axis: FloatArray, maxRadius: Float, timeS: Float) {
        GLES30.glDepthMask(false)
        for (i in 0 until RIPPLE_RINGS) {
            val phase = ((timeS * 0.85f) + i.toFloat() / RIPPLE_RINGS) % 1f
            val radius = maxRadius * (0.18f + 0.82f * phase)
            val alpha = LIQUID_ALPHA * (1f - phase) * 0.75f
            cylinderBetween3(
                add(surface, scale(axis, -0.002f)), add(surface, scale(axis, 0.002f)),
                radius = radius,
            )
            program.setModel(
                model, 0.62f, 0.86f, 0.98f, specular = 0.5f, shininess = 50f, alpha = alpha,
            )
            cyl.draw(program)
        }
        GLES30.glDepthMask(true)
    }

    /**
     * Material in flight between the module and the pail (change 14.3).
     *
     * Both directions trace the same arc, because in both cases the material travels between the
     * pail mouth and the machine standing beyond the screen edge; collecting runs along it inward,
     * pouring outward.
     *
     * The solid stream is a **cone of individually tumbling granules**. Each grain gets a
     * deterministic seed driving its phase, its lateral offset, its size, its shade and its own
     * rotation axis and rate, and the lateral spread widens along the path so the stream leaves the
     * module as a cone and funnels back into the mouth. The previous version drew axis-aligned boxes
     * with no rotation at a fixed offset, which is why it read as a flat dotted line.
     *
     * The liquid stream is a smooth tapered column with a lateral wobble, plus splash droplets
     * thrown off where it lands.
     */
    private fun drawStream(
        payload: Payload,
        mouth: FloatArray,
        axis: FloatArray,
        side: FloatArray,
        timeS: Float,
    ) {
        val solid = payload.kind == MaterialKind.SOLID
        // The far end of the arc is always outward, toward the module beyond the screen edge. Only
        // the vertical sense and the direction of travel flip.
        val out = normalize(cross(axis, side))
        val rise = if (payload.pouring) -STREAM_DROP else STREAM_DROP

        fun at(t: Float): FloatArray = add(
            add(mouth, scale(out, STREAM_REACH * t)),
            floatArrayOf(0f, rise * t * t, 0f),
        )

        if (solid) {
            for (i in 0 until POWDER_GRAINS) {
                // Deterministic per-grain jitter, so the scatter is granular but never flickers.
                val seed = (i * 0.6180339f) % 1f
                val seed2 = (i * 0.2794f) % 1f
                val phase = (timeS * POWDER_SPEED + seed) % 1f
                val t = if (payload.pouring) phase else 1f - phase

                // Cone: the spread grows with distance from the mouth, so grains fan out toward the
                // module and funnel back in toward the pail.
                val spread = CONE_SPREAD * t
                val angle = seed2 * 2f * Math.PI.toFloat() + timeS * 0.4f
                val lateral = add(
                    scale(side, cos(angle) * spread),
                    scale(axis, sin(angle) * spread * 0.55f),
                )
                val p = add(at(t), lateral)

                val grainSize = (GRAIN_MIN + (GRAIN_MAX - GRAIN_MIN) * seed) * payload.stream
                // Each granule tumbles about its own axis at its own rate.
                val spinAxis = normalize(floatArrayOf(0.4f + seed, 1f - seed2, 0.3f + seed2))
                val spinDeg = (timeS * (90f + 220f * seed2) + seed * 360f) % 360f
                modelAtRotated(p, grainSize, spinAxis, spinDeg)

                // Shade jitter so the stream has depth rather than reading as one flat colour.
                val shade = 0.86f + 0.20f * seed2
                program.setModel(
                    model,
                    POWDER[0] * shade, POWDER[1] * shade, POWDER[2] * shade,
                    specular = 0.10f, shininess = 10f,
                )
                grain.draw(program)
            }
        } else {
            drawTranslucent { alpha ->
                for (i in 0 until LIQUID_SEGMENTS) {
                    val t0 = i.toFloat() / LIQUID_SEGMENTS
                    val t1 = (i + 1).toFloat() / LIQUID_SEGMENTS
                    // The column narrows as it accelerates away from the mouth.
                    val r = LIQUID_RADIUS * (1f - 0.45f * t0) * payload.stream
                    cylinderBetween3(
                        wobbled(at(t0), side, t0, timeS),
                        wobbled(at(t1), side, t1, timeS),
                        radius = r.coerceAtLeast(0.004f),
                    )
                    program.setModel(
                        model, LIQUID[0], LIQUID[1], LIQUID[2],
                        specular = 0.6f, shininess = 54f, alpha = alpha,
                    )
                    cyl.draw(program)
                }
                // Splash droplets thrown off where the column lands.
                for (i in 0 until SPLASH_DROPS) {
                    val seed = (i * 0.6180339f) % 1f
                    val hop = (timeS * 1.9f + seed) % 1f
                    val angle = seed * 2f * Math.PI.toFloat()
                    val spray = 0.055f * hop
                    val lift = 0.045f * sin(Math.PI.toFloat() * hop)
                    val p = add(
                        add(mouth, scale(side, cos(angle) * spray)),
                        add(scale(out, sin(angle) * spray * 0.6f), scale(axis, lift)),
                    )
                    modelAt(p, 0.013f, 0.013f, 0.013f)
                    program.setModel(
                        model, LIQUID[0], LIQUID[1], LIQUID[2],
                        specular = 0.7f, shininess = 60f, alpha = alpha * (1f - hop),
                    )
                    grain.draw(program)
                }
            }
        }
    }

    /** A gentle sideways wobble along the liquid column, so it is not a dead straight tube. */
    private fun wobbled(p: FloatArray, side: FloatArray, t: Float, timeS: Float): FloatArray =
        add(p, scale(side, sin(t * 7f + timeS * 3.4f) * 0.012f))

    /**
     * Draw translucent geometry.
     *
     * Depth writes are switched off so overlapping translucent parts of the same body do not punch
     * holes in each other; depth *testing* stays on so the opaque machine still occludes them.
     */
    private inline fun drawTranslucent(body: (alpha: Float) -> Unit) {
        GLES30.glDepthMask(false)
        body(LIQUID_ALPHA)
        GLES30.glDepthMask(true)
    }

    // -- World mapping ---------------------------------------------------------------------------

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

    // -- Matrix builders -------------------------------------------------------------------------

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

    /**
     * Place a uniformly-scaled object spun about an arbitrary axis (change 14.3).
     *
     * [modelAt] has no rotation, which is why the old material stream rendered every grain
     * presenting the same face to the camera. Granules need to tumble to read as solids.
     */
    private fun modelAtRotated(p: FloatArray, size: Float, axis: FloatArray, angleDeg: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, p[0], p[1], p[2])
        Matrix.rotateM(model, 0, angleDeg, axis[0], axis[1], axis[2])
        Matrix.scaleM(model, 0, size, size, size)
    }

    /**
     * Orient the unit frustum (axis +Y) so it spans A..B, with [radius] as its widest radius.
     *
     * Same convention as [cylinderBetween3]; the taper itself lives in the mesh.
     */
    private fun frustumBetween(a: FloatArray, b: FloatArray, radius: Float) {
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

    /**
     * Place the screw-thread mesh along the rail (+X), spun about its own axis by [spinDeg].
     *
     * The spin is applied *before* the axis alignment, so it turns about the screw rather than about
     * a world axis. A helix rotated about its own axis appears to translate along it, which is what
     * makes the screw read as driving the carriage.
     */
    private fun screwModel(px: Float, py: Float, pz: Float, length: Float, radius: Float, spinDeg: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, px, py, pz)
        alignYTo(floatArrayOf(1f, 0f, 0f))
        Matrix.multiplyMM(scratch, 0, model, 0, rot, 0)
        System.arraycopy(scratch, 0, model, 0, 16)
        Matrix.rotateM(model, 0, spinDeg, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, radius * 2f, length, radius * 2f)
    }

    /**
     * Place a mesh spanning A..B, spun about its own axis (change 15.1).
     *
     * Same as [cylinderBetween3] but with a roll applied before the axis alignment, so the geometry
     * turns about the line it lies along rather than about a world axis. Used for the auger
     * flighting; [screwModel] does the same thing for the rail, which is fixed to +X.
     */
    private fun spunBetween(a: FloatArray, b: FloatArray, radius: Float, spinDeg: Float) {
        val dir = sub(b, a)
        val length = len(dir).coerceAtLeast(1e-4f)
        val mid = floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, mid[0], mid[1], mid[2])
        alignYTo(scale(dir, 1f / length))
        Matrix.multiplyMM(scratch, 0, model, 0, rot, 0)
        System.arraycopy(scratch, 0, model, 0, 16)
        Matrix.rotateM(model, 0, spinDeg, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, radius * 2f, length, radius * 2f)
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

    // -- vec helpers -----------------------------------------------------------------------------

    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun len(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

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

        /** Flat studio backdrop (change 14.1), matching LeumasColors.Backdrop (#BFC5CC). */
        val BACKDROP = floatArrayOf(0.749f, 0.773f, 0.800f)

        // Contact shadow under the machine (change 14.1).
        //
        // SHADOW_Y sits below the rail extrusion, which spans -0.055..0.045 — at the original
        // -0.005 the discs were *inside* the rail body and poked through it.
        const val SHADOW_RINGS = 12
        const val SHADOW_Y = -0.10f
        const val SHADOW_Z = 0.02f
        const val SHADOW_INNER = 0.30f
        const val SHADOW_OUTER = 1.85f
        const val SHADOW_SQUASH = 0.26f
        const val SHADOW_ALPHA = 0.055f

        /** How far behind the turret centre the counterweight drum sits (change 14.7). */
        const val DRUM_OFFSET = 0.17f

        /** Brushed steel for the pail (change 14.4). */
        val STEEL = floatArrayOf(0.62f, 0.65f, 0.70f)

        // Material colours (change 13.8).
        val POWDER = floatArrayOf(0.85f, 0.74f, 0.48f)
        val LIQUID = floatArrayOf(0.24f, 0.62f, 0.88f)
        const val LIQUID_ALPHA = 0.72f

        /**
         * Thread starts along the ball screw (change 13.5).
         *
         * Shared by the mesh and the spin rate: the lead is `span / SCREW_TURNS`, so the thread
         * advances exactly one pitch per revolution and the screw reads as driving the carriage.
         */
        const val SCREW_TURNS = 26

        const val CHAIN_LINKS = 14

        // Process vessel on the end effector (change 15.1).
        /** Opacity of the glass vessel body: enough to read as glass, clear enough to see the level. */
        const val GLASS_ALPHA = 0.40f

        const val VESSEL_RADIUS = 0.075f
        const val VESSEL_HEIGHT = 0.110f
        /** Wrist to vessel flange. Change 16.6: tightened from 0.085 so the neck is a short
         * machined section rather than a span the eye reads as a gap the vessel hangs across. */
        const val VESSEL_DROP = 0.062f

        /** Radius of the neck joining the wrist race to the vessel flange (change 16.6). */
        const val NECK_RADIUS = 0.040f
        const val HOPPER_HEIGHT = 0.045f

        // Screw auger, for dosing powder.
        const val AUGER_RADIUS = 0.030f
        const val AUGER_LENGTH = 0.105f
        const val AUGER_BACK = 0.030f
        const val AUGER_SPOUT = 0.040f

        // Peristaltic head, for dosing liquid.
        const val PUMP_RADIUS = 0.056f
        const val PUMP_WIDTH = 0.042f
        const val PUMP_ORBIT = 0.034f
        const val PUMP_ROLLER_R = 0.013f
        const val PUMP_ROLLERS = 3
        const val PUMP_TUBE_R = 0.009f
        const val PUMP_TUBE_SEGMENTS = 10
        const val PUMP_NOZZLE = 0.034f

        const val STREAM_REACH = 0.34f
        const val STREAM_DROP = 0.26f
        const val POWDER_GRAINS = 40
        const val POWDER_SPEED = 1.35f

        /** Widest lateral spread of the granule cone, at the module end (change 14.3). */
        const val CONE_SPREAD = 0.055f
        const val GRAIN_MIN = 0.011f
        const val GRAIN_MAX = 0.023f

        const val LIQUID_SEGMENTS = 14
        const val LIQUID_RADIUS = 0.026f
        const val SPLASH_DROPS = 6
        const val RIPPLE_RINGS = 3


        /** Band over which the hand's side axis blends to the turret fallback (change 13.3). */
        const val SIDE_BLEND_LO = 0.03f
        const val SIDE_BLEND_HI = 0.20f

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
