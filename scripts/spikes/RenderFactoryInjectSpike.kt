// Can we replace the RenderFactory on a SkiaLayer we did not construct?
//
// EglSkiaMpvSpike.kt proved the shipped skiko rasterises on EGL and shares that
// context with mpv at full zero-copy. To use that in the app, Compose's own
// SkiaLayer must build an EGL Redrawer instead of the GLX one -- and Compose
// constructs its SkiaLayer internally, passing no RenderFactory.
//
// Two facts make injection plausible:
//   1. Compose's layer is reachable -- WindowSkiaLayerComponent.getHierarchyRoot()
//      returns SkiaLayer publicly, and SkiaLayer extends JComponent, so it is also
//      findable by an ordinary AWT container walk.
//   2. SkiaLayer.renderFactory is `private final`, but the bytecode reads it
//      LAZILY, at createRedrawer() time, not in the constructor.
//
// So: does overwriting that field after construction, before the layer is
// realised, actually take effect? Tested on a plain SkiaLayer -- reachability is
// established separately, and this avoids needing the Compose compiler plugin.

import org.jetbrains.skiko.SkiaLayer
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun inject(layer: SkiaLayer): String = try {
    val f = SkiaLayer::class.java.getDeclaredField("renderFactory")
    f.isAccessible = true
    val original = f.get(layer)
    f.set(layer, RenderFactorySpy.wrap(original))
    "replaced (was ${original.javaClass.name})"
} catch (e: Throwable) {
    "FAILED: ${e::class.qualifiedName}: ${e.message}"
}

fun main() {
    SwingUtilities.invokeLater {
        val layer = SkiaLayer()
        println("SPIKE: inject -> ${inject(layer)}")

        val frame = JFrame("renderfactory inject spike")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.preferredSize = Dimension(320, 240)
        layer.attachTo(frame.contentPane)
        frame.pack()
        frame.isVisible = true
    }

    Thread {
        Thread.sleep(2500)
        val ok = RenderFactorySpy.called
        println(
            "RESULT renderfactory-inject ok=$ok renderApi=${RenderFactorySpy.sawApi} msg=" +
                if (ok) "private final renderFactory is read lazily, so injection works"
                else "field replaced but createRedrawer never used it",
        )
        exitProcess(if (ok) 0 else 1)
    }.start()
}
