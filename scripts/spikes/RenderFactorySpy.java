// Java half of the RenderFactory injection spike.
//
// RenderFactory and Redrawer are Kotlin `internal`: public in the bytecode, but
// Kotlin source outside skiko's module cannot name them. Java ignores that flag,
// so the interface must be implemented here. This is a real constraint on the
// eventual EGL Redrawer -- it needs a Java shim like this one, or -Xfriend-paths.
//
// wrap()/called are typed as Object/boolean so the Kotlin driver never has to
// name an internal type.

import org.jetbrains.skiko.GraphicsApi;
import org.jetbrains.skiko.RenderFactory;
import org.jetbrains.skiko.SkiaLayer;
import org.jetbrains.skiko.SkiaLayerAnalytics;
import org.jetbrains.skiko.SkiaLayerProperties;
import org.jetbrains.skiko.redrawer.Redrawer;

public class RenderFactorySpy implements RenderFactory {
    public static volatile boolean called = false;
    public static volatile String sawApi = null;

    private final RenderFactory delegate;

    private RenderFactorySpy(RenderFactory delegate) { this.delegate = delegate; }

    /** Object in/out so Kotlin callers never mention the internal RenderFactory type. */
    public static Object wrap(Object original) { return new RenderFactorySpy((RenderFactory) original); }

    @Override
    public Redrawer createRedrawer(SkiaLayer layer, GraphicsApi renderApi,
                                   SkiaLayerAnalytics analytics, SkiaLayerProperties properties) {
        called = true;
        sawApi = String.valueOf(renderApi);
        System.out.println("SPIKE: injected RenderFactory.createRedrawer called, renderApi=" + renderApi);
        // Delegating proves the hook fires without needing a working EGL Redrawer yet.
        return delegate.createRedrawer(layer, renderApi, analytics, properties);
    }
}
