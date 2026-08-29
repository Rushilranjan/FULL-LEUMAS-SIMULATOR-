package com.leumas.robot.render

import com.leumas.robot.calibration.Calibration
import com.leumas.robot.domain.ActiveTransfer
import com.leumas.robot.domain.RobotAnimationState
import com.leumas.robot.domain.RobotPhase
import com.leumas.robot.protocol.TransferKind
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Renders solved poses to an SVG so the machine can be judged without a device.
 *
 * The **geometry** here is the real thing: it runs the production [RobotPoser], [Kinematics] and
 * [ArenaGeometry], so a proportion or reach mistake shows up here rather than on the phone.
 *
 * The **shading** is a deliberate re-implementation of `RobotArtist` in SVG — same part hierarchy,
 * same palette, same extrusion and gradient treatment, but separate code. It previews the intended
 * look; it is not proof of what Compose draws. Only a device settles that.
 *
 * Output: `app/build/pose-preview.svg`.
 */
class PosePreviewTest {

    private val geometry = ArenaGeometry(widthPx = 1500f, heightPx = 780f)
    private var gradientId = 0

    private fun state(
        phase: RobotPhase,
        progress: Float,
        from: Int,
        to: Int,
        carrying: Boolean = false,
        motion: Float = 0f,
    ) = RobotAnimationState(
        phase = phase,
        phaseProgress = progress,
        overallProgress = 0.5f,
        transfer = ActiveTransfer(from, to, TransferKind.ROBOT, 0L),
        carryingPayload = carrying,
        catchingUp = false,
    ) to motion

    @Test
    fun `render pose preview sheet`() {
        val calibration = Calibration.DEFAULT

        val scenes = listOf(
            Triple("IDLE — stowed at rail centre", RobotAnimationState.IDLE, 0f),
            Triple("PICKING — Macro 1 (TOP)", state(RobotPhase.PICKING, 1f, 1, 4).first, 0.25f),
            Triple(
                "PLACING — Blender 1 (RIGHT), carrying",
                state(RobotPhase.PLACING, 1f, 1, 4, carrying = true).first, 0.15f,
            ),
            Triple(
                "MOVING — mid rail travel, carrying",
                state(RobotPhase.MOVING_TO_DESTINATION, 0.5f, 1, 4, carrying = true).first, 0.9f,
            ),
            Triple("PICKING — Liquid Macro 1 (LEFT)", state(RobotPhase.PICKING, 1f, 7, 9).first, 0.25f),
            Triple(
                "PLACING — Vessel 1 (BOTTOM), carrying",
                state(RobotPhase.PLACING, 1f, 7, 9, carrying = true).first, 0.15f,
            ),
        )

        val w = geometry.widthPx.toInt()
        val rowH = geometry.heightPx.toInt() + 26

        val body = StringBuilder()
        scenes.forEachIndexed { index, (title, animation, motion) ->
            val pose = RobotPoser().pose(animation, calibration, geometry)
                .copy(motion = motion)
            body.append("""<g transform="translate(0,${index * rowH})">""")
            body.append(panel(title, pose))
            body.append("</g>")
        }

        val svg = buildString {
            append("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" """)
            append("""height="${rowH * scenes.size}" viewBox="0 0 $w ${rowH * scenes.size}">""")
            append("<defs>").append(defs).append("</defs>")
            append("""<rect width="100%" height="100%" fill="#07090B"/>""")
            append(body)
            append("</svg>")
        }

        val out = File("build/pose-preview.svg")
        out.parentFile?.mkdirs()
        out.writeText(svg)

        scenes.forEach { (title, animation, _) ->
            val pose = RobotPoser().pose(animation, calibration, geometry)
            assertTrue("$title produced a non-finite carriage", pose.carriageX.isFinite())
            assertTrue(
                "$title parked the carriage off the rail",
                pose.carriageX >= geometry.railMinX - 1f && pose.carriageX <= geometry.railMaxX + 1f,
            )
            assertTrue(
                "$title tilted the forearm",
                kotlin.math.abs(pose.arm.wrist.y - pose.arm.gripper.y) < 0.01f,
            )
        }
        assertTrue("preview was not written", out.exists() && out.length() > 0)
    }

    // -- SVG helpers -------------------------------------------------------------------------

    private val defs = StringBuilder()

    /** Cross-axis gradient along a limb, mirroring Shade.acrossAxis. */
    private fun limbGradient(a: Vec2, b: Vec2, half: Float, hi: String, base: String, lo: String): String {
        val id = "g${gradientId++}"
        val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(0.001f)
        val nx = -(b.y - a.y) / len
        val ny = (b.x - a.x) / len
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        defs.append(
            """<linearGradient id="$id" gradientUnits="userSpaceOnUse" """ +
                """x1="${mx + nx * half}" y1="${my + ny * half}" """ +
                """x2="${mx - nx * half}" y2="${my - ny * half}">""" +
                """<stop offset="0" stop-color="$hi"/>""" +
                """<stop offset="0.30" stop-color="$base"/>""" +
                """<stop offset="0.72" stop-color="$base"/>""" +
                """<stop offset="1" stop-color="$lo"/></linearGradient>"""
        )
        return id
    }

    /** Radial shading for a round part, lit from the upper left, mirroring Shade.sphere. */
    private fun sphereGradient(c: Vec2, r: Float, hi: String, base: String, lo: String): String {
        val id = "g${gradientId++}"
        defs.append(
            """<radialGradient id="$id" gradientUnits="userSpaceOnUse" """ +
                """cx="${c.x - r * 0.38f}" cy="${c.y - r * 0.38f}" r="${r * 1.65f}">""" +
                """<stop offset="0" stop-color="$hi"/>""" +
                """<stop offset="0.45" stop-color="$base"/>""" +
                """<stop offset="1" stop-color="$lo"/></radialGradient>"""
        )
        return id
    }

    private fun verticalGradient(y0: Float, y1: Float, hi: String, base: String, lo: String): String {
        val id = "g${gradientId++}"
        defs.append(
            """<linearGradient id="$id" gradientUnits="userSpaceOnUse" x1="0" y1="$y0" x2="0" y2="$y1">""" +
                """<stop offset="0" stop-color="$hi"/>""" +
                """<stop offset="0.26" stop-color="$base"/>""" +
                """<stop offset="0.74" stop-color="$base"/>""" +
                """<stop offset="1" stop-color="$lo"/></linearGradient>"""
        )
        return id
    }

    private fun limb(
        a: Vec2, b: Vec2, wa: Float, wb: Float,
        hi: String, base: String, lo: String, h: Float,
    ): String {
        val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(0.001f)
        val nx = -(b.y - a.y) / len
        val ny = (b.x - a.x) / len
        val ha = wa / 2f
        val hb = wb / 2f
        val dx = h * Shade.DEPTH_X
        val dy = h * Shade.DEPTH_Y

        fun quad(ox: Float, oy: Float) =
            "${a.x + nx * ha + ox},${a.y + ny * ha + oy} ${b.x + nx * hb + ox},${b.y + ny * hb + oy} " +
                "${b.x - nx * hb + ox},${b.y - ny * hb + oy} ${a.x - nx * ha + ox},${a.y - ny * ha + oy}"

        val grad = limbGradient(a, b, maxOf(ha, hb), hi, base, lo)
        return buildString {
            // Extruded side wall.
            append("""<circle cx="${a.x + dx}" cy="${a.y + dy}" r="$ha" fill="$lo" fill-opacity="0.55"/>""")
            append("""<circle cx="${b.x + dx}" cy="${b.y + dy}" r="$hb" fill="$lo" fill-opacity="0.55"/>""")
            append("""<polygon points="${quad(dx, dy)}" fill="$lo" fill-opacity="0.55"/>""")
            // Lit face.
            append("""<circle cx="${a.x}" cy="${a.y}" r="$ha" fill="$base"/>""")
            append("""<circle cx="${b.x}" cy="${b.y}" r="$hb" fill="$base"/>""")
            append("""<polygon points="${quad(0f, 0f)}" fill="url(#$grad)"/>""")
            // Rim light.
            append(
                """<line x1="${a.x + nx * ha * 0.74f}" y1="${a.y + ny * ha * 0.74f}" """ +
                    """x2="${b.x + nx * hb * 0.74f}" y2="${b.y + ny * hb * 0.74f}" """ +
                    """stroke="#9FD2FF" stroke-opacity="0.30" stroke-width="${maxOf(1f, h * 0.0022f)}" """ +
                    """stroke-linecap="round"/>"""
            )
        }
    }

    private fun joint(c: Vec2, r: Float, body: String, rim: String, bolts: Int, h: Float): String {
        val dx = h * Shade.DEPTH_X
        val dy = h * Shade.DEPTH_Y
        val rimG = sphereGradient(c, r, rim, "#6E747B", "#2E3238")
        val faceG = sphereGradient(c, r * 0.84f, "#5A5A5A", body, "#0C0C0C")
        val hubG = sphereGradient(c, r * 0.34f, rim, "#7A8088", "#3A3E44")
        return buildString {
            append("""<circle cx="${c.x + dx}" cy="${c.y + dy}" r="$r" fill="#0A0A0A"/>""")
            append("""<circle cx="${c.x}" cy="${c.y}" r="$r" fill="url(#$rimG)"/>""")
            append("""<circle cx="${c.x}" cy="${c.y}" r="${r * 0.84f}" fill="url(#$faceG)"/>""")
            append("""<circle cx="${c.x}" cy="${c.y}" r="${r * 0.34f}" fill="url(#$hubG)"/>""")
            repeat(bolts) { i ->
                val t = (i.toFloat() / bolts) * 2f * Math.PI.toFloat()
                val bx = c.x + r * 0.64f * cos(t)
                val by = c.y + r * 0.64f * sin(t)
                val br = r * 0.095f
                append("""<circle cx="${bx + br * 0.3f}" cy="${by + br * 0.3f}" r="$br" fill="#2A2E33"/>""")
                append("""<circle cx="$bx" cy="$by" r="$br" fill="#B7BDC4"/>""")
            }
            append(
                """<path d="M ${c.x - r} ${c.y} A $r $r 0 0 1 ${c.x} ${c.y - r}" fill="none" """ +
                    """stroke="#9FD2FF" stroke-opacity="0.35" stroke-width="${maxOf(1f, h * 0.002f)}"/>"""
            )
        }
    }

    // -- One panel ---------------------------------------------------------------------------

    private fun panel(title: String, pose: RobotPose): String = buildString {
        val g = geometry
        val h = g.heightPx
        val w = g.widthPx
        val a = pose.arm
        val cx = pose.carriageX
        val railY = g.railY
        val horizon = railY - h * 0.02f
        val dx = h * Shade.DEPTH_X
        val dy = h * Shade.DEPTH_Y
        val facing = pose.facing

        // --- Backdrop: wall above the horizon, receding floor below it.
        val wallG = verticalGradient(0f, horizon, "#0A0C0F", "#111820", "#171D24")
        val floorG = verticalGradient(horizon, h, "#1B222A", "#12171D", "#090B0E")
        append("""<rect x="0" y="0" width="$w" height="$horizon" fill="url(#$wallG)"/>""")
        append("""<rect x="0" y="$horizon" width="$w" height="${h - horizon}" fill="url(#$floorG)"/>""")

        // Perspective floor grid converging on the horizon.
        for (i in -14..14) {
            val xb = w / 2f + i * (w / 14f) * 0.62f
            append(
                """<line x1="${w / 2f + (xb - w / 2f) * 0.06f}" y1="$horizon" x2="$xb" y2="$h" """ +
                    """stroke="#2C343D" stroke-opacity="0.16" stroke-width="1"/>"""
            )
        }
        for (i in 1..9) {
            val t = Math.pow(i / 9.0, 2.1).toFloat()
            val y = horizon + (h - horizon) * t
            append(
                """<line x1="0" y1="$y" x2="$w" y2="$y" stroke="#2C343D" """ +
                    """stroke-opacity="${0.10f + 0.14f * t}" stroke-width="1"/>"""
            )
        }
        append("""<line x1="0" y1="$horizon" x2="$w" y2="$horizon" stroke="#4FA8E8" stroke-opacity="0.10" stroke-width="2"/>""")

        append(
            """<text x="18" y="30" fill="#8A96A3" font-family="monospace" font-size="17">$title</text>"""
        )

        // Calibrated interaction points, for judging reach. (Not drawn in the real app.)
        Calibration.DEFAULT.points.forEach { (slot, point) ->
            val p = g.toPx(point)
            append("""<circle cx="${p.x}" cy="${p.y}" r="6" fill="none" stroke="#4FA8E8" stroke-opacity="0.5" stroke-width="2"/>""")
            append("""<text x="${p.x + 10}" y="${p.y + 5}" fill="#4FA8E8" fill-opacity="0.6" font-family="monospace" font-size="12">$slot</text>""")
        }

        // --- Floor reflection.
        fun mirror(p: Vec2) = Vec2(p.x, railY + (railY - p.y) * 0.42f + h * 0.055f)
        listOf(
            Triple(mirror(a.shoulder), mirror(a.elbow), h * 0.030f),
            Triple(mirror(a.elbow), mirror(a.wrist), h * 0.024f),
            Triple(mirror(a.wrist), mirror(a.gripper), h * 0.018f),
        ).forEach { (p, q, sw) ->
            append(
                """<line x1="${p.x}" y1="${p.y}" x2="${q.x}" y2="${q.y}" stroke="#C41E24" """ +
                    """stroke-opacity="0.10" stroke-width="$sw" stroke-linecap="round"/>"""
            )
        }

        // --- Motion streaks.
        if (pose.motion > 0.08f) {
            val dir = -1f
            repeat(5) { i ->
                val t = (i + 1f) / 5f
                val len = h * 0.10f * pose.motion * t
                val alpha = 0.20f * pose.motion * (1f - t)
                val oy = (i - 2.5f) * h * 0.014f
                append(
                    """<line x1="${cx + dir * h * 0.06f}" y1="${railY + oy - h * 0.10f}" """ +
                        """x2="${cx + dir * (h * 0.06f + len)}" y2="${railY + oy - h * 0.10f}" """ +
                        """stroke="#4FA8E8" stroke-opacity="$alpha" stroke-width="${maxOf(1f, h * 0.0035f)}" """ +
                        """stroke-linecap="round"/>"""
                )
            }
        }

        // --- Contact shadow.
        val shadowG = "s${gradientId++}"
        defs.append(
            """<radialGradient id="$shadowG"><stop offset="0" stop-color="#000" stop-opacity="0.55"/>""" +
                """<stop offset="1" stop-color="#000" stop-opacity="0"/></radialGradient>"""
        )
        append(
            """<ellipse cx="${cx + h * 0.012f}" cy="${railY + h * 0.052f}" """ +
                """rx="${h * 0.135f}" ry="${h * 0.026f}" fill="url(#$shadowG)"/>"""
        )

        // --- Rail with lead screw.
        val railG = verticalGradient(railY - h * 0.018f, railY + h * 0.018f, "#78808A", "#3A424B", "#20262C")
        append("""<rect x="${g.railMinX - h * 0.06f}" y="${railY - h * 0.018f}" width="${g.railMaxX - g.railMinX + h * 0.12f}" height="${h * 0.036f}" fill="url(#$railG)"/>""")
        val screwG = verticalGradient(railY - h * 0.011f, railY + h * 0.011f, "#F2F5F8", "#C8CDD2", "#78808A")
        append("""<rect x="${g.railMinX - h * 0.06f}" y="${railY - h * 0.011f}" width="${g.railMaxX - g.railMinX + h * 0.12f}" height="${h * 0.022f}" fill="url(#$screwG)"/>""")
        var sx = g.railMinX - h * 0.06f
        while (sx < g.railMaxX + h * 0.06f) {
            append(
                """<line x1="${sx - h * 0.012f}" y1="${railY - h * 0.011f}" x2="${sx + h * 0.012f}" """ +
                    """y2="${railY + h * 0.011f}" stroke="#78808A" stroke-opacity="0.85" stroke-width="${maxOf(1f, h * 0.0022f)}"/>"""
            )
            sx += h * 0.026f
        }
        listOf(g.railMinX - h * 0.06f, g.railMaxX + h * 0.06f).forEach { ex ->
            append("""<rect x="${ex - h * 0.015f}" y="${railY - h * 0.030f}" width="${h * 0.030f}" height="${h * 0.060f}" fill="#22272D" stroke="#0E1013"/>""")
            append("""<circle cx="$ex" cy="$railY" r="${h * 0.008f}" fill="#78808A"/>""")
        }

        // --- Carriage and mounting plate.
        val carG = verticalGradient(railY - h * 0.026f, railY + h * 0.026f, "#5A636D", "#3C444D", "#23282E")
        append("""<rect x="${cx - h * 0.0575f}" y="${railY - h * 0.026f}" width="${h * 0.115f}" height="${h * 0.052f}" fill="url(#$carG)" stroke="#171B20" stroke-width="2" rx="3"/>""")
        val plateG = verticalGradient(railY - h * 0.042f, railY - h * 0.026f, "#4A4A4A", "#2C3137", "#1E1E1E")
        append("""<rect x="${cx - h * 0.0675f}" y="${railY - h * 0.042f}" width="${h * 0.135f}" height="${h * 0.016f}" fill="url(#$plateG)" rx="2"/>""")

        // --- Base plate, feet, pedestal.
        val plateTop = railY - h * 0.030f
        val plateW = h * 0.150f
        val plateH = h * 0.018f
        listOf(-0.42f, 0.42f).forEach { f ->
            append("""<ellipse cx="${cx + plateW * f}" cy="${plateTop + plateH + h * 0.003f}" rx="${h * 0.021f}" ry="${h * 0.008f}" fill="#161616"/>""")
        }
        val bpG = verticalGradient(plateTop, plateTop + plateH, "#6A727B", "#262B31", "#1E1E1E")
        append("""<rect x="${cx - plateW / 2f + dx}" y="${plateTop + dy}" width="$plateW" height="$plateH" fill="#0C0E10"/>""")
        append("""<rect x="${cx - plateW / 2f}" y="$plateTop" width="$plateW" height="$plateH" fill="url(#$bpG)"/>""")

        val pedH = h * 0.072f
        val pedTop = plateTop - pedH
        val topW = h * 0.088f
        val botW = h * 0.128f
        fun ped(ox: Float, oy: Float) =
            "${cx - topW / 2f + ox},${pedTop + oy} ${cx + topW / 2f + ox},${pedTop + oy} " +
                "${cx + botW / 2f + ox},${plateTop + oy} ${cx - botW / 2f + ox},${plateTop + oy}"
        val pedG = "p${gradientId++}"
        defs.append(
            """<linearGradient id="$pedG" gradientUnits="userSpaceOnUse" x1="${cx - botW / 2f}" y1="$pedTop" x2="${cx + botW / 2f}" y2="$plateTop">""" +
                """<stop offset="0" stop-color="#525C66"/><stop offset="0.22" stop-color="#2E353C"/>""" +
                """<stop offset="0.78" stop-color="#1B2026"/><stop offset="1" stop-color="#101418"/></linearGradient>"""
        )
        append("""<polygon points="${ped(dx, dy)}" fill="#0A0C0E"/>""")
        append("""<polygon points="${ped(0f, 0f)}" fill="url(#$pedG)"/>""")
        listOf(-0.22f, 0.22f).forEach { f ->
            append("""<rect x="${cx + botW * f - h * 0.011f}" y="${pedTop + pedH * 0.26f}" width="${h * 0.022f}" height="${pedH * 0.46f}" rx="${h * 0.010f}" fill="#05070A" stroke="#39414A" stroke-opacity="0.55"/>""")
        }

        // Slewing bearing ring.
        val ringW = h * 0.098f
        val ringH = h * 0.016f
        val ringG = verticalGradient(pedTop - ringH, pedTop, "#FFFFFF", "#E6E6E4", "#9C9C98")
        append("""<rect x="${cx - ringW / 2f + dx}" y="${pedTop - ringH + dy}" width="$ringW" height="$ringH" rx="${h * 0.005f}" fill="#6E6E6A"/>""")
        append("""<rect x="${cx - ringW / 2f}" y="${pedTop - ringH}" width="$ringW" height="$ringH" rx="${h * 0.005f}" fill="url(#$ringG)"/>""")

        // --- Turret, counterweight, drive flange.
        val ringTop = pedTop - ringH
        val turretH = h * 0.082f
        val turretTop = ringTop - turretH
        val turretW = h * 0.086f

        val drumW = h * 0.052f
        val drumH = h * 0.044f
        val drumX = cx - facing * (turretW * 0.62f + drumW * 0.34f)
        val drumY = turretTop + turretH * 0.18f
        val drumG = "d${gradientId++}"
        defs.append(
            """<linearGradient id="$drumG" gradientUnits="userSpaceOnUse" x1="${drumX - drumW / 2f}" y1="0" x2="${drumX + drumW / 2f}" y2="0">""" +
                """<stop offset="0" stop-color="#6A6A6A"/><stop offset="0.28" stop-color="#2B2B2B"/>""" +
                """<stop offset="0.72" stop-color="#1A1A1A"/><stop offset="1" stop-color="#0B0B0B"/></linearGradient>"""
        )
        append("""<rect x="${drumX - drumW / 2f + dx}" y="${drumY + dy}" width="$drumW" height="$drumH" rx="${h * 0.010f}" fill="#080808"/>""")
        append("""<rect x="${drumX - drumW / 2f}" y="$drumY" width="$drumW" height="$drumH" rx="${h * 0.010f}" fill="url(#$drumG)"/>""")

        fun turret(ox: Float, oy: Float) =
            "${cx - turretW * 0.44f + ox},${turretTop + oy} ${cx + turretW * 0.44f + ox},${turretTop + oy} " +
                "${cx + turretW * 0.54f + ox},${ringTop + oy} ${cx - turretW * 0.54f + ox},${ringTop + oy}"
        val turretG = "t${gradientId++}"
        defs.append(
            """<linearGradient id="$turretG" gradientUnits="userSpaceOnUse" x1="${cx - turretW * 0.54f}" y1="$turretTop" x2="${cx + turretW * 0.54f}" y2="$ringTop">""" +
                """<stop offset="0" stop-color="#F04A50"/><stop offset="0.26" stop-color="#C41E24"/>""" +
                """<stop offset="0.74" stop-color="#C41E24"/><stop offset="1" stop-color="#7E1015"/></linearGradient>"""
        )
        append("""<polygon points="${turret(dx, dy)}" fill="#4A0A0D"/>""")
        append("""<polygon points="${turret(0f, 0f)}" fill="url(#$turretG)"/>""")
        append("""<polygon points="${turret(0f, 0f)}" fill="none" stroke="#9FD2FF" stroke-opacity="0.22" stroke-width="2"/>""")
        listOf(-0.20f, 0.20f).forEach { f ->
            append("""<rect x="${cx + turretW * f - h * 0.008f}" y="${turretTop + turretH * 0.30f}" width="${h * 0.016f}" height="${turretH * 0.44f}" rx="${h * 0.008f}" fill="#480A0D"/>""")
        }
        append(joint(Vec2(cx + facing * turretW * 0.30f, turretTop + turretH * 0.42f), h * 0.026f, "#2A2A2A", "#B9BEC4", 10, h))

        // --- Arm.
        val red = Triple("#F04A50", "#C41E24", "#7E1015")
        val rodStart = Vec2(a.shoulder.x - facing * h * 0.030f, a.shoulder.y - h * 0.026f)
        val rodEnd = Vec2(
            a.elbow.x + (a.wrist.x - a.elbow.x) * 0.14f,
            a.elbow.y + (a.wrist.y - a.elbow.y) * 0.14f - h * 0.020f,
        )
        append(limb(rodStart, rodEnd, h * 0.0080f, h * 0.0080f, "#FFFFFF", "#C8CDD2", "#78808A", h))

        // Swan-neck lower arm, bowed away from the tie-rod.
        run {
            val len = hypot(a.elbow.x - a.shoulder.x, a.elbow.y - a.shoulder.y).coerceAtLeast(0.001f)
            val nx = -(a.elbow.y - a.shoulder.y) / len
            val ny = (a.elbow.x - a.shoulder.x) / len
            val bow = h * 0.045f * facing
            val ctrlX = (a.shoulder.x + a.elbow.x) / 2f + nx * bow
            val ctrlY = (a.shoulder.y + a.elbow.y) / 2f + ny * bow
            val width = h * 0.040f
            val grad = limbGradient(a.shoulder, a.elbow, width, red.first, red.second, red.third)
            fun curve(ox: Float, oy: Float) =
                "M ${a.shoulder.x + ox} ${a.shoulder.y + oy} Q ${ctrlX + ox} ${ctrlY + oy} ${a.elbow.x + ox} ${a.elbow.y + oy}"
            append("""<path d="${curve(dx, dy)}" fill="none" stroke="#450A0D" stroke-width="$width" stroke-linecap="round"/>""")
            append("""<path d="${curve(0f, 0f)}" fill="none" stroke="url(#$grad)" stroke-width="$width" stroke-linecap="round"/>""")
            append("""<path d="${curve(-width * 0.16f, -width * 0.20f)}" fill="none" stroke="#F04A50" stroke-opacity="0.45" stroke-width="${width * 0.20f}" stroke-linecap="round"/>""")
            append("""<path d="${curve(-width * 0.26f, -width * 0.30f)}" fill="none" stroke="#9FD2FF" stroke-opacity="0.22" stroke-width="${width * 0.10f}" stroke-linecap="round"/>""")
        }

        append(limb(a.elbow, a.wrist, h * 0.030f, h * 0.0246f, red.first, red.second, red.third, h))

        // Cable loop.
        run {
            val sag = h * (0.026f + 0.020f * pose.motion)
            val sway = h * 0.012f * pose.motion
            val d = "M ${a.elbow.x} ${a.elbow.y} Q ${(a.elbow.x + a.wrist.x) / 2f + sway} ${(a.elbow.y + a.wrist.y) / 2f + sag} ${a.wrist.x} ${a.wrist.y}"
            append("""<path d="$d" fill="none" stroke="#0B0B0B" stroke-opacity="0.92" stroke-width="${maxOf(1.5f, h * 0.0060f)}" stroke-linecap="round"/>""")
            append("""<path d="$d" fill="none" stroke="#3A3A3A" stroke-opacity="0.55" stroke-width="${maxOf(1f, h * 0.0022f)}" stroke-linecap="round"/>""")
        }

        append(limb(a.wrist, a.gripper, h * 0.024f, h * 0.0211f, red.first, red.second, red.third, h))

        append(joint(a.shoulder, h * 0.030f, "#1C1C1C", "#A8AEB4", 8, h))
        append(joint(a.elbow, h * 0.024f, "#1C1C1C", "#A8AEB4", 6, h))
        append(joint(a.wrist, h * 0.017f, "#1C1C1C", "#9AA0A6", 5, h))

        // --- End effector.
        val bodyW = h * 0.032f
        val bodyH = h * 0.026f
        val eeG = verticalGradient(a.gripper.y - bodyH / 2f, a.gripper.y + bodyH / 2f, "#6E6E6E", "#232323", "#0D0D0D")
        append("""<rect x="${a.gripper.x - bodyW / 2f + dx}" y="${a.gripper.y - bodyH / 2f + dy}" width="$bodyW" height="$bodyH" rx="${h * 0.005f}" fill="#080808"/>""")
        append("""<rect x="${a.gripper.x - bodyW / 2f}" y="${a.gripper.y - bodyH / 2f}" width="$bodyW" height="$bodyH" rx="${h * 0.005f}" fill="url(#$eeG)"/>""")
        val spread = h * (0.008f + 0.012f * pose.gripperOpen)
        listOf(-1f, 1f).forEach { s ->
            val fx = a.gripper.x + s * spread
            append("""<rect x="${fx - h * 0.0045f}" y="${a.gripper.y + bodyH * 0.35f}" width="${h * 0.009f}" height="${h * 0.028f}" rx="${h * 0.002f}" fill="#2A2A2A"/>""")
        }
        if (pose.carrying) {
            val boxW = h * 0.034f
            val boxH = h * 0.030f
            val top = a.gripper.y + bodyH * 0.45f
            val boxG = "b${gradientId++}"
            defs.append(
                """<linearGradient id="$boxG" gradientUnits="userSpaceOnUse" x1="${a.gripper.x - boxW / 2f}" y1="$top" x2="${a.gripper.x + boxW / 2f}" y2="${top + boxH}">""" +
                    """<stop offset="0" stop-color="#F0D9A6"/><stop offset="0.30" stop-color="#D8B679"/>""" +
                    """<stop offset="1" stop-color="#8E7040"/></linearGradient>"""
            )
            append("""<rect x="${a.gripper.x - boxW / 2f + dx * 1.6f}" y="${top + dy * 1.6f}" width="$boxW" height="$boxH" rx="3" fill="#3A2E16"/>""")
            append("""<rect x="${a.gripper.x - boxW / 2f}" y="$top" width="$boxW" height="$boxH" rx="3" fill="url(#$boxG)"/>""")
            append("""<line x1="${a.gripper.x - boxW / 2f}" y1="${top + boxH * 0.42f}" x2="${a.gripper.x + boxW / 2f}" y2="${top + boxH * 0.42f}" stroke="#6B5327" stroke-opacity="0.8" stroke-width="2"/>""")
        }

        // Arena border marking the screen edge the arm reaches past.
        append("""<rect x="1" y="1" width="${w - 2}" height="${h - 2}" fill="none" stroke="#2C343D" stroke-width="2"/>""")
    }
}
