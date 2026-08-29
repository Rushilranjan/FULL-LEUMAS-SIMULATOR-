package com.leumas.robot.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import com.leumas.robot.ui.theme.LeumasColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Draws the 4-axis palletiser, modelled on the reference machine.
 *
 * Part hierarchy, ground up: bolted base plate on four disc feet → boxy pedestal with lightening
 * cutouts → white slewing bearing ring (A1) → red turret casting with lightening slots and a
 * bolted drive flange → black counterweight drum at the rear → shoulder housing (A2) → curved
 * swan-neck lower arm → elbow housing (A3) → upper arm with a chrome parallelogram tie-rod running
 * above it → horizontal forearm → wrist rotary actuator (A4) → end effector.
 *
 * **Depth.** Every part is extruded: a dark side wall offset along the light direction, then the
 * lit face on top. Combined with cross-axis gradients, radial shading on round parts and a cool
 * rim light on upper-left edges, the flat vector machine reads as solid.
 *
 * Base yaw is rendered as a horizontal squash of the turret group. A side elevation cannot show
 * in-plane rotation honestly, and `cos(yaw)` sweeping through zero reads convincingly as the
 * turret swinging edge-on and round again.
 */
object RobotArtist {

    fun DrawScope.drawRobot(pose: RobotPose, geometry: ArenaGeometry) {
        val h = geometry.heightPx
        val railY = geometry.railY
        val cx = pose.carriageX

        // Contact shadow anchors the machine to the floor.
        drawContactShadow(
            centre = Vec2(cx + h * 0.012f, railY + h * 0.052f),
            halfWidth = h * 0.135f,
            halfHeight = h * 0.026f,
            strength = 1f,
        )

        drawPedestal(cx, railY, h)

        // Everything above the slewing ring rotates with A1.
        val absSquash = abs(pose.yawSquash).coerceAtLeast(0.06f)
        scale(scaleX = absSquash, scaleY = 1f, pivot = Offset(cx, railY)) {
            drawTurret(cx, railY, h, facing = pose.facing)
        }

        drawArm(pose, geometry)
    }

    // -- Base ------------------------------------------------------------------------------

    private fun DrawScope.drawPedestal(cx: Float, railY: Float, h: Float) {
        val d = Shade.depth(h)
        val plateTop = railY - h * 0.030f
        val plateW = h * 0.150f
        val plateH = h * 0.018f

        // Four disc levelling feet.
        listOf(-0.42f, 0.42f).forEach { f ->
            val fx = cx + plateW * f
            drawOval(
                brush = Brush.radialGradient(
                    0f to Color(0xFF3A3A3A),
                    1f to Color(0xFF0E0E0E),
                    center = Offset(fx - h * 0.006f, plateTop + plateH),
                    radius = h * 0.024f,
                ),
                topLeft = Offset(fx - h * 0.021f, plateTop + plateH - h * 0.005f),
                size = Size(h * 0.042f, h * 0.016f),
            )
        }

        // Bolted base plate, extruded.
        drawRect(
            color = Color(0xFF0C0E10),
            topLeft = Offset(cx - plateW / 2f + d.x, plateTop + d.y),
            size = Size(plateW, plateH),
        )
        drawRect(
            brush = Shade.vertical(
                plateTop, plateTop + plateH,
                Color(0xFF6A727B), Color(0xFF262B31), LeumasColors.Black,
            ),
            topLeft = Offset(cx - plateW / 2f, plateTop),
            size = Size(plateW, plateH),
        )
        listOf(-0.40f, -0.16f, 0.16f, 0.40f).forEach { f ->
            val p = Vec2(cx + plateW * f, plateTop + plateH / 2f)
            drawCircle(
                Shade.sphere(p, h * 0.005f, Color.White, LeumasColors.Chrome, LeumasColors.ChromeLo),
                h * 0.005f,
                p.toOffset(),
            )
        }

        // Boxy pedestal frame, tapering upward.
        val pedH = h * 0.072f
        val pedTop = plateTop - pedH
        val topW = h * 0.088f
        val botW = h * 0.128f

        fun frame(ox: Float, oy: Float) = Path().apply {
            moveTo(cx - topW / 2f + ox, pedTop + oy)
            lineTo(cx + topW / 2f + ox, pedTop + oy)
            lineTo(cx + botW / 2f + ox, plateTop + oy)
            lineTo(cx - botW / 2f + ox, plateTop + oy)
            close()
        }
        drawPath(frame(d.x, d.y), Color(0xFF0A0C0E))
        drawPath(
            frame(0f, 0f),
            Brush.linearGradient(
                0.00f to Color(0xFF525C66),
                0.22f to Color(0xFF2E353C),
                0.78f to Color(0xFF1B2026),
                1.00f to Color(0xFF101418),
                start = Offset(cx - botW / 2f, pedTop),
                end = Offset(cx + botW / 2f, plateTop),
            ),
        )

        // Elongated lightening cutouts, with an inner shadow lip.
        listOf(-0.22f, 0.22f).forEach { f ->
            val x = cx + botW * f - h * 0.011f
            val y = pedTop + pedH * 0.26f
            drawRoundRect(
                color = Color(0xFF05070A),
                topLeft = Offset(x, y),
                size = Size(h * 0.022f, pedH * 0.46f),
                cornerRadius = CornerRadius(h * 0.010f),
            )
            drawRoundRect(
                color = Color(0xFF39414A).copy(alpha = 0.55f),
                topLeft = Offset(x, y),
                size = Size(h * 0.022f, pedH * 0.46f),
                cornerRadius = CornerRadius(h * 0.010f),
                style = Stroke(width = maxOf(1f, h * 0.0016f)),
            )
        }

        // White slewing bearing ring — the A1 axis.
        val ringW = h * 0.098f
        val ringH = h * 0.016f
        drawRoundRect(
            color = Color(0xFF6E6E6A),
            topLeft = Offset(cx - ringW / 2f + d.x, pedTop - ringH + d.y),
            size = Size(ringW, ringH),
            cornerRadius = CornerRadius(h * 0.005f),
        )
        drawRoundRect(
            brush = Shade.vertical(
                pedTop - ringH, pedTop,
                Color(0xFFFFFFFF), LeumasColors.BearingRing, Color(0xFF9C9C98),
            ),
            topLeft = Offset(cx - ringW / 2f, pedTop - ringH),
            size = Size(ringW, ringH),
            cornerRadius = CornerRadius(h * 0.005f),
        )
        (-3..3).forEach { i ->
            val p = Vec2(cx + i * ringW * 0.13f, pedTop - ringH / 2f)
            drawCircle(Color(0xFF7E7E7A), h * 0.0030f, p.toOffset())
        }
    }

    // -- Turret ----------------------------------------------------------------------------

    private fun DrawScope.drawTurret(cx: Float, railY: Float, h: Float, facing: Float) {
        val d = Shade.depth(h)
        val ringTop = railY - h * 0.030f - h * 0.072f - h * 0.016f
        val turretH = h * 0.082f
        val turretTop = ringTop - turretH
        val turretW = h * 0.086f

        // Counterweight drum, cantilevered behind the turret. Drawn as a real cylinder.
        val drumW = h * 0.052f
        val drumH = h * 0.044f
        val drumX = cx - facing * (turretW * 0.62f + drumW * 0.34f)
        val drumY = turretTop + turretH * 0.18f
        drawRoundRect(
            color = Color(0xFF080808),
            topLeft = Offset(drumX - drumW / 2f + d.x, drumY + d.y),
            size = Size(drumW, drumH),
            cornerRadius = CornerRadius(h * 0.010f),
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                0.00f to Color(0xFF6A6A6A),
                0.28f to Color(0xFF2B2B2B),
                0.72f to Color(0xFF1A1A1A),
                1.00f to Color(0xFF0B0B0B),
                start = Offset(drumX - drumW / 2f, drumY),
                end = Offset(drumX + drumW / 2f, drumY),
            ),
            topLeft = Offset(drumX - drumW / 2f, drumY),
            size = Size(drumW, drumH),
            cornerRadius = CornerRadius(h * 0.010f),
        )
        // End cap, so the drum reads as a tube rather than a slab.
        drawOval(
            brush = Shade.sphere(
                Vec2(drumX - facing * drumW * 0.42f, drumY + drumH / 2f), drumH * 0.5f,
                Color(0xFF5C5C5C), Color(0xFF232323), Color(0xFF0D0D0D),
            ),
            topLeft = Offset(drumX - facing * drumW * 0.42f - drumW * 0.10f, drumY),
            size = Size(drumW * 0.20f, drumH),
        )

        // Red turret casting.
        fun casting(ox: Float, oy: Float) = Path().apply {
            moveTo(cx - turretW * 0.44f + ox, turretTop + oy)
            lineTo(cx + turretW * 0.44f + ox, turretTop + oy)
            lineTo(cx + turretW * 0.54f + ox, ringTop + oy)
            lineTo(cx - turretW * 0.54f + ox, ringTop + oy)
            close()
        }
        drawPath(casting(d.x, d.y), Color(0xFF4A0A0D))
        drawPath(
            casting(0f, 0f),
            Brush.linearGradient(
                0.00f to LeumasColors.RobotRedHi,
                0.26f to LeumasColors.RobotRed,
                0.74f to LeumasColors.RobotRed,
                1.00f to LeumasColors.RobotRedLo,
                start = Offset(cx - turretW * 0.54f, turretTop),
                end = Offset(cx + turretW * 0.54f, ringTop),
            ),
        )
        drawPath(
            casting(0f, 0f),
            Shade.RIM.copy(alpha = 0.22f),
            style = Stroke(width = maxOf(1f, h * 0.0018f)),
        )

        // Elongated lightening slots.
        listOf(-0.20f, 0.20f).forEach { f ->
            drawRoundRect(
                color = Color(0xFF480A0D),
                topLeft = Offset(cx + turretW * f - h * 0.008f, turretTop + turretH * 0.30f),
                size = Size(h * 0.016f, turretH * 0.44f),
                cornerRadius = CornerRadius(h * 0.008f),
            )
        }

        // Bolted drive flange — the shoulder gearbox face.
        drawJointHousing(
            centre = Vec2(cx + facing * turretW * 0.30f, turretTop + turretH * 0.42f),
            radius = h * 0.026f,
            body = Color(0xFF2A2A2A),
            rim = Color(0xFFB9BEC4),
            boltColor = Color(0xFF8E949B),
            arenaHeight = h,
            bolts = 10,
        )
    }

    // -- Arm -------------------------------------------------------------------------------

    private fun DrawScope.drawArm(pose: RobotPose, geometry: ArenaGeometry) {
        val h = geometry.heightPx
        val arm = pose.arm
        val shoulder = arm.shoulder
        val elbow = arm.elbow
        val wrist = arm.wrist
        val gripper = arm.gripper
        val facing = pose.facing

        val lowerW = h * 0.040f
        val upperW = h * 0.030f
        val foreW = h * 0.024f

        // Chrome parallelogram tie-rod. Endpoints derive from the same joints as the arm, so it
        // stays geometrically true in every pose.
        val rodStart = Vec2(shoulder.x - facing * h * 0.030f, shoulder.y - h * 0.026f)
        val rodEnd = Vec2(
            elbow.x + (wrist.x - elbow.x) * 0.14f,
            elbow.y + (wrist.y - elbow.y) * 0.14f - h * 0.020f,
        )
        drawTaperedLimb(
            rodStart, rodEnd, h * 0.0080f, h * 0.0080f,
            Color.White, LeumasColors.Chrome, LeumasColors.ChromeLo,
            arenaHeight = h,
        )
        listOf(rodStart, rodEnd).forEach { p ->
            drawCircle(
                Shade.sphere(p, h * 0.0085f, Color.White, LeumasColors.Chrome, LeumasColors.ChromeLo),
                h * 0.0085f,
                p.toOffset(),
            )
        }

        // Swan-neck lower arm: a curved casting, bowed away from the tie-rod.
        drawSwanNeck(shoulder, elbow, lowerW, h, facing)

        drawTaperedLimb(
            elbow, wrist, upperW, upperW * 0.82f,
            LeumasColors.RobotRedHi, LeumasColors.RobotRed, LeumasColors.RobotRedLo,
            arenaHeight = h,
        )

        // Cable loop, swaying with movement.
        drawCableLoop(elbow, wrist, h, pose.motion)

        // Horizontal forearm — held level by the parallelogram in every pose.
        drawTaperedLimb(
            wrist, gripper, foreW, foreW * 0.88f,
            LeumasColors.RobotRedHi, LeumasColors.RobotRed, LeumasColors.RobotRedLo,
            arenaHeight = h,
        )

        drawJointHousing(
            shoulder, h * 0.030f,
            body = Color(0xFF1C1C1C), rim = Color(0xFFA8AEB4),
            boltColor = Color(0xFF8E949B), arenaHeight = h, bolts = 8,
        )
        drawJointHousing(
            elbow, h * 0.024f,
            body = Color(0xFF1C1C1C), rim = Color(0xFFA8AEB4),
            boltColor = Color(0xFF8E949B), arenaHeight = h, bolts = 6,
        )
        drawJointHousing(
            wrist, h * 0.017f,
            body = Color(0xFF1C1C1C), rim = Color(0xFF9AA0A6),
            boltColor = Color(0xFF8E949B), arenaHeight = h, bolts = 5,
        )

        drawEndEffector(gripper, h, pose.gripperOpen, pose.carrying, pose.motion)
    }

    /** The distinctive bowed lower arm, drawn as a thick curved casting with an extruded shadow. */
    private fun DrawScope.drawSwanNeck(
        shoulder: Vec2,
        elbow: Vec2,
        width: Float,
        h: Float,
        facing: Float,
    ) {
        val d = Shade.depth(h)
        val dx = elbow.x - shoulder.x
        val dy = elbow.y - shoulder.y
        val len = hypot(dx, dy).coerceAtLeast(0.001f)
        val nx = -dy / len
        val ny = dx / len
        val bow = h * 0.045f * facing
        val ctrl = Vec2(
            (shoulder.x + elbow.x) / 2f + nx * bow,
            (shoulder.y + elbow.y) / 2f + ny * bow,
        )

        fun curve(ox: Float, oy: Float) = Path().apply {
            moveTo(shoulder.x + ox, shoulder.y + oy)
            quadraticTo(ctrl.x + ox, ctrl.y + oy, elbow.x + ox, elbow.y + oy)
        }

        drawPath(
            curve(d.x, d.y),
            Color(0xFF450A0D),
            style = Stroke(width = width, cap = StrokeCap.Round),
        )
        drawPath(
            curve(0f, 0f),
            Shade.acrossAxis(
                shoulder, elbow, width,
                LeumasColors.RobotRedHi, LeumasColors.RobotRed, LeumasColors.RobotRedLo,
            ),
            style = Stroke(width = width, cap = StrokeCap.Round),
        )
        // Specular ridge along the casting.
        drawPath(
            curve(-width * 0.16f, -width * 0.20f),
            LeumasColors.RobotRedHi.copy(alpha = 0.45f),
            style = Stroke(width = width * 0.20f, cap = StrokeCap.Round),
        )
        drawPath(
            curve(-width * 0.26f, -width * 0.30f),
            Shade.RIM.copy(alpha = 0.22f),
            style = Stroke(width = width * 0.10f, cap = StrokeCap.Round),
        )
    }

    private fun DrawScope.drawCableLoop(a: Vec2, b: Vec2, h: Float, motion: Float) {
        // The loop swings outward as the machine moves, which reads as real mass in motion.
        val sag = h * (0.026f + 0.020f * motion)
        val sway = h * 0.012f * motion
        val path = Path().apply {
            moveTo(a.x, a.y)
            quadraticTo((a.x + b.x) / 2f + sway, (a.y + b.y) / 2f + sag, b.x, b.y)
        }
        drawPath(
            path,
            Color(0xFF0B0B0B).copy(alpha = 0.92f),
            style = Stroke(width = maxOf(1.5f, h * 0.0060f), cap = StrokeCap.Round),
        )
        drawPath(
            path,
            Color(0xFF3A3A3A).copy(alpha = 0.55f),
            style = Stroke(width = maxOf(1f, h * 0.0022f), cap = StrokeCap.Round),
        )
    }

    /** Wrist rotary actuator (A4) plus a two-finger end effector. */
    private fun DrawScope.drawEndEffector(
        gripper: Vec2,
        h: Float,
        open: Float,
        carrying: Boolean,
        motion: Float,
    ) {
        val d = Shade.depth(h)
        val bodyW = h * 0.032f
        val bodyH = h * 0.026f

        drawRoundRect(
            color = Color(0xFF080808),
            topLeft = Offset(gripper.x - bodyW / 2f + d.x, gripper.y - bodyH / 2f + d.y),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(h * 0.005f),
        )
        drawRoundRect(
            brush = Shade.vertical(
                gripper.y - bodyH / 2f, gripper.y + bodyH / 2f,
                Color(0xFF6E6E6E), Color(0xFF232323), Color(0xFF0D0D0D),
            ),
            topLeft = Offset(gripper.x - bodyW / 2f, gripper.y - bodyH / 2f),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(h * 0.005f),
        )
        drawCircle(
            Shade.sphere(gripper, h * 0.007f, Color.White, Color(0xFF9AA0A6), Color(0xFF4A4F55)),
            h * 0.007f,
            gripper.toOffset(),
        )

        // Two fingers, spreading with `open`.
        val spread = h * (0.008f + 0.012f * open)
        val fingerLen = h * 0.028f
        listOf(-1f, 1f).forEach { s ->
            val fx = gripper.x + s * spread
            drawRoundRect(
                color = Color(0xFF0A0A0A),
                topLeft = Offset(fx - h * 0.0045f + d.x, gripper.y + bodyH * 0.35f + d.y),
                size = Size(h * 0.009f, fingerLen),
                cornerRadius = CornerRadius(h * 0.002f),
            )
            drawRoundRect(
                brush = Shade.vertical(
                    gripper.y + bodyH * 0.35f, gripper.y + bodyH * 0.35f + fingerLen,
                    Color(0xFF5A5A5A), Color(0xFF2A2A2A), Color(0xFF141414),
                ),
                topLeft = Offset(fx - h * 0.0045f, gripper.y + bodyH * 0.35f),
                size = Size(h * 0.009f, fingerLen),
                cornerRadius = CornerRadius(h * 0.002f),
            )
        }

        if (carrying) {
            val boxW = h * 0.034f
            val boxH = h * 0.030f
            val top = gripper.y + bodyH * 0.45f
            // Slight lag, so the payload feels like it has mass.
            val lag = h * 0.006f * motion

            drawRoundRect(
                color = Color(0xFF3A2E16),
                topLeft = Offset(gripper.x - boxW / 2f + d.x * 1.6f, top + d.y * 1.6f),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(h * 0.003f),
            )
            drawRoundRect(
                brush = Brush.linearGradient(
                    0.00f to Color(0xFFF0D9A6),
                    0.30f to Color(0xFFD8B679),
                    1.00f to Color(0xFF8E7040),
                    start = Offset(gripper.x - boxW / 2f, top),
                    end = Offset(gripper.x + boxW / 2f, top + boxH),
                ),
                topLeft = Offset(gripper.x - boxW / 2f + lag, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(h * 0.003f),
            )
            // Strapping.
            drawLine(
                Color(0xFF6B5327).copy(alpha = 0.8f),
                Offset(gripper.x - boxW / 2f + lag, top + boxH * 0.42f),
                Offset(gripper.x + boxW / 2f + lag, top + boxH * 0.42f),
                strokeWidth = maxOf(1f, h * 0.0020f),
            )
        }
    }

    /**
     * Motion streaks trailing the carriage.
     *
     * Drawn behind the machine, fading with distance, and only while it is genuinely moving —
     * a stationary robot shows none.
     */
    fun DrawScope.drawMotionStreaks(pose: RobotPose, geometry: ArenaGeometry) {
        if (pose.motion < 0.08f || abs(pose.carriageVelocity) < 0.01f) return
        val h = geometry.heightPx
        val dir = if (pose.carriageVelocity >= 0f) -1f else 1f
        val y = geometry.railY

        repeat(STREAK_COUNT) { i ->
            val t = (i + 1f) / STREAK_COUNT
            val len = h * 0.10f * pose.motion * t
            val alpha = 0.20f * pose.motion * (1f - t)
            val offsetY = (i - STREAK_COUNT / 2f) * h * 0.014f
            drawLine(
                color = LeumasColors.Info.copy(alpha = alpha),
                start = Offset(pose.carriageX + dir * h * 0.06f, y + offsetY - h * 0.10f),
                end = Offset(pose.carriageX + dir * (h * 0.06f + len), y + offsetY - h * 0.10f),
                strokeWidth = maxOf(1f, h * 0.0035f),
                cap = StrokeCap.Round,
            )
        }
    }

    /**
     * Faint mirrored reflection of the machine on the floor.
     *
     * Sells the floor as a real surface rather than a backdrop. Kept extremely low contrast so it
     * never competes with the machine itself.
     */
    fun DrawScope.drawFloorReflection(pose: RobotPose, geometry: ArenaGeometry) {
        val h = geometry.heightPx
        val railY = geometry.railY
        val arm = pose.arm

        fun mirror(p: Vec2) = Vec2(p.x, railY + (railY - p.y) * REFLECTION_SQUASH + h * 0.055f)

        val alpha = 0.10f
        listOf(
            Triple(mirror(arm.shoulder), mirror(arm.elbow), h * 0.030f),
            Triple(mirror(arm.elbow), mirror(arm.wrist), h * 0.024f),
            Triple(mirror(arm.wrist), mirror(arm.gripper), h * 0.018f),
        ).forEach { (a, b, w) ->
            drawLine(
                color = LeumasColors.RobotRed.copy(alpha = alpha),
                start = a.toOffset(),
                end = b.toOffset(),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }
    }

    /** Subtle idle breathing so a parked machine never looks like a frozen image. */
    fun idleBob(nowMs: Long, arenaHeight: Float): Float =
        sin(nowMs / 900f) * arenaHeight * 0.0016f

    private const val STREAK_COUNT = 5
    private const val REFLECTION_SQUASH = 0.42f
}
