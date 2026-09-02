package com.leumas.robot.render3d

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * Hosts the 3-D robot scene ([SceneRenderer]) inside the Compose tree.
 *
 * [poseProvider] is polled by the renderer on the GL thread each frame. It reads the latest pose the
 * frame loop has published; reading a Compose snapshot value off the composition like this does not
 * subscribe the composable, so vsync-rate pose updates never recompose the UI. The view renders
 * continuously and is paused/resumed with the host lifecycle so it never leaks the GL context.
 */
@Composable
fun RobotGlView(
    poseProvider: () -> Robot3DPose?,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val lifecycle = remember(view) { view.findViewTreeLifecycleOwner()?.lifecycle }
    val holder = remember { GlViewHolder() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                // 4x MULTISAMPLING (change 43).
                //
                // The scene has never been antialiased -- there was no config chooser at all, so
                // EGL handed back whatever it liked, which on this device is a single-sample
                // buffer. Every silhouette in the frame was therefore a hard staircase, and it
                // showed worst exactly where the complaint was: the base is built from boxes whose
                // long straight edges run at shallow angles to the pixel grid, which is the case
                // aliasing is most visible in. No amount of geometry or material work fixes that;
                // it is a framebuffer property.
                //
                // The chooser walks 4x -> 2x -> none, so a device that cannot give us multisampled
                // configs still gets a valid surface rather than a crash.
                setEGLConfigChooser(MultisampleConfigChooser())
                // Sit above the window's (opaque) background but below the window's own content, so
                // the transparent Compose overlay and HUD cards composite on top of the 3-D scene
                // while the scene itself is still visible. Paired with a transparent root background
                // in MainActivity. (Flagged in the plan as needing on-device confirmation.)
                setZOrderMediaOverlay(true)
                setRenderer(SceneRenderer(poseProvider))
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                holder.view = this
            }
        },
    )

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view?.onPause()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            holder.view?.onPause()
            holder.view = null
        }
    }
}

private class GlViewHolder {
    var view: GLSurfaceView? = null
}

/**
 * Pick the best multisampled EGL config the device will give us (change 43).
 *
 * Requested in descending order so we degrade rather than fail: 4x, then 2x, then a plain
 * single-sample config identical to what EGL was handing back before.
 */
private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (samples in intArrayOf(4, 2, 0)) {
            val base = intArrayOf(
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
            )
            val spec =
                if (samples > 0) {
                    base + intArrayOf(
                        EGL10.EGL_SAMPLE_BUFFERS, 1,
                        EGL10.EGL_SAMPLES, samples,
                        EGL10.EGL_NONE,
                    )
                } else {
                    base + intArrayOf(EGL10.EGL_NONE)
                }

            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) continue
            val configs = arrayOfNulls<EGLConfig>(count[0])
            if (!egl.eglChooseConfig(display, spec, configs, count[0], count)) continue
            configs.firstOrNull { it != null }?.let { return it }
        }
        error("no usable EGL config")
    }

    private companion object {
        /** Not exposed on EGL10; both are stable EGL constants. */
        const val EGL_RENDERABLE_TYPE = 0x3040
        const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}
