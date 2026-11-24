package app.qrikkiyomi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pre-delivery UI verification (docs/VERIFICATION.md): the real
 * MainActivity driven on the JVM — tab navigation works, and the
 * create form's draft survives a tab round-trip (the R2-6 behaviour,
 * which the pure unit tests cannot see because it lives in how the
 * activity holds the screens' state).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "w411dp-h891dp-night",
    application = QrTestApplication::class,
)
class MainActivityNavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsOnScanTab_withPermissionRationale() {
        rule.onNodeWithText("QRを読むためにカメラを使います。画像は端末の外に送りません。")
            .assertIsDisplayed()
    }

    @Test
    fun tabRoundTrip_keepsCreateDraft() {
        rule.onNodeWithText("作る").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("https://example.com/draft")

        rule.onNodeWithText("宝箱").performClick()
        rule.onNodeWithText("まだ何もありません。スキャンするか、作ってみてください")
            .assertIsDisplayed()

        // Back on the create tab, the half-typed form is still there.
        rule.onNodeWithText("作る").performClick()
        rule.onNodeWithText("https://example.com/draft").assertIsDisplayed()
    }

    @Test
    fun settingsTab_opens() {
        rule.onNodeWithText("設定").performClick()
        rule.onNodeWithText("スキャンしたら自動で履歴に保存").assertIsDisplayed()
        rule.onNodeWithText("バックアップを書き出す").assertIsDisplayed()
    }
}
