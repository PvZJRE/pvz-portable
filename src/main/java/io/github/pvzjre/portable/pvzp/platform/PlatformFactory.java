package io.github.pvzjre.portable.pvzp.platform;

import io.github.pvzjre.portable.pvzp.platform.sdl.SdlPlatform;
import io.github.pvzjre.portable.pvzp.platform.sdl.SdlRenderer;
import io.github.pvzjre.portable.sexy.graphics.RendererBackend;

/**
 * Creates the platform backend and the drawing backend for the running game.
 *
 * <p>Selection rules (mirroring the upstream port's "GLES2 first, desktop GL fallback" spirit):
 * <ul>
 *   <li>{@code -Dpvz.renderer=software} forces the SDL software renderer (used by headless tests).</li>
 *   <li>{@code -Dpvz.renderer=<driver>} asks SDL for a specific renderer driver (e.g. {@code metal},
 *       {@code opengl}, {@code software}).</li>
 *   <li>default: SDL picks the driver, falling back to the software renderer when no GPU driver is
 *       available (e.g. {@code SDL_VIDEODRIVER=dummy} in CI).</li>
 * </ul>
 *
 * <p>On macOS the JVM must be started with {@code -XstartOnFirstThread} (SDL requirement), which the
 * Gradle {@code run}/{@code test} tasks set.
 */
public final class PlatformFactory {

    private PlatformFactory() {
    }

    public static String configuredDriver() {
        return System.getProperty("pvz.renderer", "");
    }

    /** Creates (but does not initialize) the platform backend. */
    public static SdlPlatform createPlatform() {
        return new SdlPlatform();
    }

    /**
     * Initializes the given platform if needed and creates a renderer with the requested logical
     * resolution (upstream's fixed 800x600 screen space).
     */
    public static RendererBackend createRenderer(PlatformBackend platform, int logicalWidth, int logicalHeight) {
        if (!(platform instanceof SdlPlatform sdlPlatform)) {
            throw new IllegalArgumentException("unknown platform backend: " + platform.getClass().getName());
        }
        // Selection order: an explicit -Dpvz.renderer wins, then the --enable/--disable3Dacceleration
        // launch parameters override the auto answer, and with neither given SDL picks the device's
        // own - a GPU driver when the machine has one, its software renderer when it does not (the
        // platform falls further back to a window-surface software renderer if even that refuses).
        // The resolved state lands in SexyAppBase.m3DAccelerated afterwards.
        String driver = configuredDriver();
        if (driver.isEmpty()) {
            io.github.pvzjre.portable.sexy.SexyAppBase aApp = io.github.pvzjre.portable.sexy.SexyAppBase.current;
            driver = aApp != null && Boolean.FALSE.equals(aApp.getForced3DAcceleration()) ? "software" : null;
        }
        return sdlPlatform.createRenderer(logicalWidth, logicalHeight, driver, true);
    }

    /** Software renderer without a window; used by headless tests and tools. */
    public static RendererBackend createSoftwareRenderer(int width, int height) {
        SdlRenderer renderer = SdlRenderer.software(width, height);
        renderer.setLogicalSize(width, height);
        return renderer;
    }
}
