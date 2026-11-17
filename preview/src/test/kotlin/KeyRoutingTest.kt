import androidx.compose.ui.input.key.Key
import com.milolab.btctv.ui.KeyIntent
import com.milolab.btctv.ui.SettingsBackAction
import com.milolab.btctv.ui.routeKey
import com.milolab.btctv.ui.settingsBackAction
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyRoutingTest {

    @Test
    fun mainScreen_mapsToMainActions() {
        assertEquals(KeyIntent.ChangeTf(-1), routeKey(false, false, Key.DirectionLeft))
        assertEquals(KeyIntent.ChangeTf(1), routeKey(false, false, Key.DirectionRight))
        assertEquals(KeyIntent.Zoom(-1), routeKey(false, false, Key.DirectionUp))
        assertEquals(KeyIntent.Zoom(1), routeKey(false, false, Key.DirectionDown))
        assertEquals(KeyIntent.OpenSettings, routeKey(false, false, Key.DirectionCenter))
        assertEquals(KeyIntent.OpenSettings, routeKey(false, false, Key.Enter))
    }

    @Test
    fun mainScreen_menuAndSearchOpenInstrumentSearch() {
        assertEquals(KeyIntent.OpenSearch, routeKey(false, false, Key.Menu))
        assertEquals(KeyIntent.OpenSearch, routeKey(false, false, Key.Search))
    }

    /** 这条就是投影仪上踩到的 bug：设置打开后方向键还在改周期 */
    @Test
    fun settingsOpen_neverTouchesMainScreen() {
        assertEquals(KeyIntent.None, routeKey(true, false, Key.DirectionLeft))
        assertEquals(KeyIntent.None, routeKey(true, false, Key.DirectionRight))
        assertEquals(KeyIntent.None, routeKey(true, false, Key.DirectionUp))
        assertEquals(KeyIntent.None, routeKey(true, false, Key.DirectionDown))
        assertEquals(KeyIntent.None, routeKey(true, false, Key.DirectionCenter))
        assertEquals(KeyIntent.None, routeKey(true, false, Key.Enter))
        assertEquals(KeyIntent.None, routeKey(true, false, Key.Menu))
        assertEquals(KeyIntent.None, routeKey(true, false, Key.Search))
    }

    /** 搜索弹层打开时同理：按键只能给弹层的焦点系统 */
    @Test
    fun searchOpen_neverTouchesMainScreen() {
        assertEquals(KeyIntent.None, routeKey(false, true, Key.DirectionLeft))
        assertEquals(KeyIntent.None, routeKey(false, true, Key.DirectionRight))
        assertEquals(KeyIntent.None, routeKey(false, true, Key.DirectionUp))
        assertEquals(KeyIntent.None, routeKey(false, true, Key.DirectionDown))
        assertEquals(KeyIntent.None, routeKey(false, true, Key.DirectionCenter))
        assertEquals(KeyIntent.None, routeKey(false, true, Key.Enter))
        assertEquals(KeyIntent.None, routeKey(false, true, Key.Menu))
        assertEquals(KeyIntent.None, routeKey(false, true, Key.Search))
    }

    /** BACK 先收起参数行，再关整个设置面板。 */
    @Test
    fun backCollapsesExpandedParamsBeforeClosingSettings() {
        assertEquals(SettingsBackAction.COLLAPSE_PARAMS, settingsBackAction("rsi"))
        assertEquals(SettingsBackAction.CLOSE_SETTINGS, settingsBackAction(null))
    }
}
