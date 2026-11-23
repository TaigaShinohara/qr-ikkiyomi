package app.qrikkiyomi

import android.app.Application
import com.google.mlkit.common.MlKit

/**
 * Test-only Application for the Robolectric tests (docs/VERIFICATION.md).
 *
 * On a device, ML Kit initialises itself through its manifest provider.
 * Robolectric does not run that provider, and ScanScreen asks ML Kit for
 * a barcode client while composing — without this, every test that
 * touches the scan screen dies with "MlKitContext has not been
 * initialized". Initialising ML Kit here mirrors what the provider
 * would do; no code is ever scanned in these tests.
 */
class QrTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            MlKit.initialize(this)
        } catch (e: IllegalStateException) {
            // Robolectric reuses the sandbox classloader across test
            // methods but creates a fresh Application per method, so
            // onCreate runs again while ML Kit's static context is
            // already initialised. That state is exactly what we want.
        }
    }
}
