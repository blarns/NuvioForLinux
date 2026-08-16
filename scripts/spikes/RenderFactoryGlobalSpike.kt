// Can the RenderFactory be replaced GLOBALLY, before any SkiaLayer exists?
//
// RenderFactoryInjectSpike proved per-instance injection, but that only works on
// a layer we construct. The app needs Compose's own SkiaLayer -- built
// internally and realised before we could reach it -- to pick up an EGL
// Redrawer. That means replacing RenderFactory.Companion.Default up front, in
// main(), before application {}.
//
// Default is `private static final`, so Field.set throws and it takes Unsafe.
// If this does not work the whole app-integration design changes shape, so it is
// worth five minutes before writing the Redrawer.
//
// The SkiaLayer here is constructed with NO per-instance injection -- it must
// pick the spy up purely from the global default.

import org.jetbrains.skiko.SkiaLayer
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main() {
    val err = RenderFactorySpy.installGlobally()
    println("SPIKE: installGlobally -> ${err ?: "ok"}")
    if (err != null) {
        println("RESULT renderfactory-global ok=false msg=$err")
        exitProcess(1)
    }

    SwingUtilities.invokeLater {
        val layer = SkiaLayer() // deliberately NOT injected per-instance
        val frame = JFrame("renderfactory global spike")
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
            "RESULT renderfactory-global ok=$ok renderApi=${RenderFactorySpy.sawApi} msg=" +
                if (ok) "global Default overwrite works -- the app can hook before Compose builds its layer"
                else "Default overwritten but SkiaLayer did not use it",
        )
        exitProcess(if (ok) 0 else 1)
    }.start()
}
