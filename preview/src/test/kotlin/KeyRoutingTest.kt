import androidx.compose.ui.input.key.Key
import com.milolab.btctv.ui.KeyIntent
import com.milolab.btctv.ui.routeKey
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyRoutingTest {

    @Test
    fun settingsClosed_mapsToMainActions() {
        assertEquals(KeyIntent.ChangeTf(-1), routeKey(false, Key.DirectionLeft))
        assertEquals(KeyIntent.ChangeTf(1), routeKey(false, Key.DirectionRight))
        assertEquals(KeyIntent.Zoom(-1), routeKey(false, Key.DirectionUp))
        assertEquals(KeyIntent.Zoom(1), routeKey(false, Key.DirectionDown))
        assertEquals(KeyIntent.OpenSettings, routeKey(false, Key.DirectionCenter))
        assertEquals(KeyIntent.OpenSettings, routeKey(false, Key.Enter))
        assertEquals(KeyIntent.OpenSettings, routeKey(false, Key.Menu))
    }

    /** 这条就是投影仪上踩到的 bug：设置打开后方向键还在改周期 */
    @Test
    fun settingsOpen_neverTouchesMainScreen() {
        assertEquals(KeyIntent.None, routeKey(true, Key.DirectionLeft))
        assertEquals(KeyIntent.None, routeKey(true, Key.DirectionRight))
        assertEquals(KeyIntent.None, routeKey(true, Key.DirectionUp))
        assertEquals(KeyIntent.None, routeKey(true, Key.DirectionDown))
        assertEquals(KeyIntent.None, routeKey(true, Key.DirectionCenter))
        assertEquals(KeyIntent.None, routeKey(true, Key.Enter))
    }
}
