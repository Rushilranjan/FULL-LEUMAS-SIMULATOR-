package com.leumas.robot.render3d

import android.opengl.GLSurfaceView
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
