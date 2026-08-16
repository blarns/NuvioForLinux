// Can Skia's GL interface be assembled from a JNA callback instead of a native shim?
//
// EglSkiaSpike proved makeGLWithInterface works, but it got its proc-address
// function from a hand-written .so. If a JNA callback trampoline works instead,
// the app ships NO new native artifact -- big packaging win for both the .deb and
// the AppImage, and one less thing to cross-compile.
//
// Skia calls this getter once per GL entry point while assembling the interface
// (hundreds of calls), from the calling thread.

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface
import kotlin.system.exitProcess

interface Egl : Library {
    fun eglGetProcAddress(name: String): Pointer?
    fun eglGetCurrentContext(): Pointer?
}

interface Shim2 : Library {
    fun egl_shim_setup(): Int
    fun egl_shim_error(): String
}

// Matches Skia's GrGLFuncPtr(*)(void* ctx, const char* name).
interface GetProc : Callback {
    fun invoke(ctx: Pointer?, name: String): Pointer?
}

fun main() {
    val egl = Native.load("EGL", Egl::class.java)
    // Reuse the shim purely to get a current EGL context; the point under test is
    // the getter, not the EGL setup.
    val shim = Native.load("eglskiashim", Shim2::class.java)
    if (shim.egl_shim_setup() != 0) error("egl setup: ${shim.egl_shim_error()}")

    var calls = 0
    val getProc = object : GetProc {
        override fun invoke(ctx: Pointer?, name: String): Pointer? {
            calls++
            return egl.eglGetProcAddress(name)
        }
    }
    val fnPtr = CallbackReference.getFunctionPointer(getProc)

    val ok = try {
        val iface = GLAssembledInterface.createFromNativePointers(0L, Pointer.nativeValue(fnPtr))
        val ctx = DirectContext.makeGLWithInterface(iface)
        // A DirectContext that assembled from a broken getter would be null or throw.
        println("SPIKE: DirectContext=$ctx after $calls getter calls")
        calls > 50
    } catch (e: Throwable) {
        println("SPIKE: threw after $calls getter calls: ${e::class.qualifiedName}: ${e.message}")
        false
    }
    println(
        "RESULT jna-getproc ok=$ok calls=$calls msg=" +
            if (ok) "JNA callback works as Skia's proc-address fn -- no native shim needed"
            else "JNA callback not viable; keep the C shim",
    )
    exitProcess(if (ok) 0 else 1)
}
