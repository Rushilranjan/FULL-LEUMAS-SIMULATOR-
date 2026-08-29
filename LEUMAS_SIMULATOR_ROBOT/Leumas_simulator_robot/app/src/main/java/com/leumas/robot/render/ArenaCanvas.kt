package com.leumas.robot.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import com.leumas.robot.calibration.Calibration
import com.leumas.robot.calibration.EdgePoint
import com.leumas.robot.protocol.SlotMap
import com.leumas.robot.render.ConveyorArtist.drawConveyorFlow
import com.leumas.robot.render.RailArtist.drawCarriage
import com.leumas.robot.render.RailArtist.drawRail
import com.leumas.robot.render.RobotArtist.drawFloorReflection
import com.leumas.robot.render.RobotArtist.drawMotionStreaks
import com.leumas.robot.render.RobotArtist.drawRobot
import com.leumas.robot.ui.theme.LeumasColors
import kotlin.math.pow

/** One frame's worth of things to draw. Produced by the frame loop, consumed by the Canvas. */
data class ArenaFrame(
    val pose: RobotPose? = null,
    val conveyor: ConveyorFlow? = null,
    val dimRobot: Boolean = false,
    /**
     * Resolution-independent 3-D robot pose (post-demo change 10.3). Consumed by the OpenGL scene
     * ([com.leumas.robot.render3d.RobotGlView]); the 2-D [pose] above is retained for the pose
     * preview/diagnostic tests and as a fallback renderer.
     */
    val robot3d: com.leumas.robot.render3d.Robot3DPose? = null,
)

/** Calibration overlay state. Non-null only while the operator is calibrating. */
data class CalibrationOverlay(
    val calibration: Calibration,
    val selectedSlot: Int,
)

/**
 * The robot's workspace.
 *
 * The canvas fills the whole surface on purpose: the screen edge *is* the boundary between the
 * visualisation and the real room, so the arena cannot be letterboxed.
 *
 * [frame] is a [State] read only inside the draw lambda, so each animation frame repaints the
 * canvas without recomposing the surrounding UI tree.
 */
@Composable
fun ArenaCanvas(
    frame: State<ArenaFrame>,
    calibrationOverlay: CalibrationOverlay?,
    modifier: Modifier = Modifier,
    onCalibrationTap: ((EdgePoint) -> Unit)? = null,
) {
    Canvas(
        modifier = modifier.then(
            if (onCalibrationTap != null) {
                Modifier.pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val nx = offset.x / size.width.toFloat()
                        val ny = offset.y / size.height.toFloat()
                        onCalibrationTap(EdgePoint.snapToNearestEdge(nx, ny))
                    }
                }
            } else {
                Modifier
            }
        )
    ) {
        val geometry = ArenaGeometry(size.width, size.height)
        val f = frame.value

        drawBackdrop(geometry)

        // Clipping to the arena is what creates the illusion: the arm is genuinely cut off at the
        // boundary, so it reads as reaching out of the window toward a real machine.
        clipRect {
            f.conveyor?.let { drawConveyorFlow(it, geometry) }

            f.pose?.let { pose ->
                drawFloorReflection(pose, geometry)
                drawMotionStreaks(pose, geometry)
                drawRail(geometry, pose.carriageX)
                drawCarriage(geometry, pose.carriageX)
                if (f.dimRobot) {
                    // Parked during a conveyor transfer: visibly not participating.
                    drawRobot(pose, geometry)
                    drawRect(LeumasColors.Background.copy(alpha = 0.48f))
                } else {
                    drawRobot(pose, geometry)
                }
            }

            calibrationOverlay?.let { drawCalibrationOverlay(it, geometry) }
        }

        drawVignette(geometry)
    }
}

/**
 * Transparent 2-D overlay drawn on top of the 3-D robot ([com.leumas.robot.render3d.RobotGlView]).
 *
 * The 3-D scene owns the floor, rail and machine now; this layer only adds the things that live in
 * screen space regardless of the 3-D camera: the conveyor swipe between calibrated points, the
 * calibration markers, the corner vignette, and — in calibration mode — the tap handling. Nothing
 * here paints an opaque background, so the GL scene shows through.
 */
@Composable
fun ArenaOverlay(
    frame: State<ArenaFrame>,
    calibrationOverlay: CalibrationOverlay?,
    modifier: Modifier = Modifier,
    onCalibrationTap: ((EdgePoint) -> Unit)? = null,
) {
    Canvas(
        modifier = modifier.then(
            if (onCalibrationTap != null) {
                Modifier.pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val nx = offset.x / size.width.toFloat()
                        val ny = offset.y / size.height.toFloat()
                        onCalibrationTap(EdgePoint.snapToNearestEdge(nx, ny))
                    }
                }
            } else {
                Modifier
            }
        )
    ) {
        val geometry = ArenaGeometry(size.width, size.height)
        val f = frame.value
        f.conveyor?.let { drawConveyorFlow(it, geometry) }
        calibrationOverlay?.let { drawCalibrationOverlay(it, geometry) }
        drawVignette(geometry)
    }
}

/**
 * Backdrop: a dark wall above the rail and a receding floor below it.
 *
 * The floor grid converges toward a horizon at the rail line, which gives the scene a ground plane
 * for the machine to stand on rather than floating on a flat field.
 */
private fun DrawScope.drawBackdrop(geometry: ArenaGeometry) {
    val h = geometry.heightPx
    val w = geometry.widthPx
    val horizon = geometry.railY - h * 0.02f

    // Wall.
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF0A0C0F),
            1f to Color(0xFF171D24),
            startY = 0f,
            endY = horizon,
        )
    )
    // Floor.
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color(0xFF1B222A),
            1f to Color(0xFF090B0E),
            startY = horizon,
            endY = h,
        ),
        topLeft = Offset(0f, horizon),
        size = androidx.compose.ui.geometry.Size(w, h - horizon),
    )

    // Pool of light behind the machine.
    drawRect(
        Brush.radialGradient(
            0f to Color(0xFF2A3542).copy(alpha = 0.55f),
            1f to Color.Transparent,
            center = Offset(w / 2f, horizon),
            radius = w * 0.55f,
        )
    )

    val grid = LeumasColors.Outline.copy(alpha = 0.30f)

    // Receding depth lines, converging on the horizon.
    val vanishX = w / 2f
    for (i in -GRID_COLUMNS..GRID_COLUMNS) {
        val xAtBottom = vanishX + i * (w / GRID_COLUMNS) * 0.62f
        drawLine(
            color = grid.copy(alpha = 0.16f),
            start = Offset(vanishX + (xAtBottom - vanishX) * 0.06f, horizon),
            end = Offset(xAtBottom, h),
            strokeWidth = 1f,
        )
    }

    // Horizontal rows, spaced so they bunch up toward the horizon.
    for (i in 1..GRID_ROWS) {
        val t = (i.toFloat() / GRID_ROWS).pow(2.1f)
        val y = horizon + (h - horizon) * t
        drawLine(
            color = grid.copy(alpha = 0.10f + 0.14f * t),
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = 1f,
        )
    }

    // Horizon glow.
    drawLine(
        color = LeumasColors.Info.copy(alpha = 0.10f),
        start = Offset(0f, horizon),
        end = Offset(w, horizon),
        strokeWidth = maxOf(1f, h * 0.002f),
    )
}

/** Corner falloff, so attention stays centre-stage. */
private fun DrawScope.drawVignette(geometry: ArenaGeometry) {
    drawRect(
        Brush.radialGradient(
            0.55f to Color.Transparent,
            1.0f to Color.Black.copy(alpha = 0.42f),
            center = Offset(geometry.widthPx / 2f, geometry.railY),
            radius = geometry.widthPx * 0.72f,
        )
    )
}

/**
 * Calibration markers.
 *
 * Only ever drawn in calibration mode. During normal operation the modules must be completely
 * invisible — the whole point is that they exist in the room, not on the screen.
 */
private fun DrawScope.drawCalibrationOverlay(
    overlay: CalibrationOverlay,
    geometry: ArenaGeometry,
) {
    val h = geometry.heightPx

    SlotMap.all.forEach { spec ->
        val point = overlay.calibration.points[spec.slot] ?: return@forEach
        val p = geometry.toPx(point)
        val selected = spec.slot == overlay.selectedSlot
        val color = when {
            selected -> LeumasColors.Info
            spec.isSpecialStation -> LeumasColors.OnSurfaceDim
            else -> LeumasColors.Running
        }
        val r = if (selected) h * 0.022f else h * 0.014f

        drawCircle(color.copy(alpha = 0.22f), r * 1.9f, p.toOffset())
        drawCircle(color, r, p.toOffset(), style = Stroke(width = maxOf(2f, h * 0.0035f)))
        drawCircle(color, r * 0.28f, p.toOffset())

        if (selected) {
            // Show where the arm will actually reach, past the boundary.
            val reach = geometry.overshoot(point)
            drawLine(
                color = LeumasColors.Info.copy(alpha = 0.7f),
                start = p.toOffset(),
                end = reach.toOffset(),
                strokeWidth = maxOf(2f, h * 0.004f),
            )
            // And where the carriage will park to service it.
            val cx = geometry.carriageXFor(point)
            drawLine(
                color = LeumasColors.Info.copy(alpha = 0.35f),
                start = Offset(cx, geometry.railY),
                end = p.toOffset(),
                strokeWidth = maxOf(1f, h * 0.002f),
            )
        }
    }
}

private const val GRID_COLUMNS = 14
private const val GRID_ROWS = 9
