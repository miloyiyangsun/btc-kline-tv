import com.milolab.btctv.ui.board.BoardIntent
import com.milolab.btctv.ui.board.BoardKey
import com.milolab.btctv.ui.board.BoardMode
import com.milolab.btctv.ui.board.moduleSupportsTimeframe
import com.milolab.btctv.ui.board.layout.FocusDirection
import com.milolab.btctv.ui.board.layout.ModuleType
import com.milolab.btctv.ui.board.layout.StatKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardKeysTest {

    private fun board(
        key: BoardKey,
        chart: Boolean = true,
        main: Boolean = false,
        neighbor: Boolean = false,
        longPress: Boolean = false
    ) = com.milolab.btctv.ui.board.routeBoardKey(
        BoardMode.BOARD, key, chart, main, neighbor, longPress
    )

    @Test
    fun horizontalWithNeighborMovesFocus() {
        assertEquals(BoardIntent.MoveFocus(FocusDirection.Left), board(BoardKey.Left, neighbor = true))
        assertEquals(BoardIntent.MoveFocus(FocusDirection.Right), board(BoardKey.Right, neighbor = true))
    }

    @Test
    fun horizontalWithoutNeighborFallsBackToTimeframe() {
        assertEquals(BoardIntent.ChangeTf(-1), board(BoardKey.Left))
        assertEquals(BoardIntent.ChangeTf(1), board(BoardKey.Right))
    }

    @Test
    fun longPressForcesTimeframeEvenWhenNeighborOrNonChart() {
        assertEquals(BoardIntent.ChangeTf(-1), board(BoardKey.Left, neighbor = true, longPress = true))
        assertEquals(BoardIntent.ChangeTf(1), board(BoardKey.Right, neighbor = true, longPress = true))
        assertEquals(BoardIntent.ChangeTf(1), board(BoardKey.Right, chart = false, longPress = true))
    }

    @Test
    fun nonChartWithoutNeighborShowsHint() {
        assertEquals(BoardIntent.HintNoTimeframe, board(BoardKey.Right, chart = false))
    }

    @Test
    fun verticalAlwaysMovesFocus() {
        assertEquals(BoardIntent.MoveFocus(FocusDirection.Up), board(BoardKey.Up, neighbor = true))
        assertEquals(BoardIntent.MoveFocus(FocusDirection.Down), board(BoardKey.Down))
    }

    @Test
    fun okFullscreensMainChartAndSearchesOtherCells() {
        assertEquals(BoardIntent.EnterFullscreen, board(BoardKey.Ok, main = true))
        assertEquals(BoardIntent.OpenSlotSearch, board(BoardKey.Ok, main = false))
        assertEquals(BoardIntent.OpenSlotSearch, board(BoardKey.Ok, chart = false))
    }

    @Test
    fun menuOpensSettingsAndBackLeavesToSystem() {
        assertEquals(BoardIntent.OpenSettings, board(BoardKey.Menu))
        assertEquals(BoardIntent.None, board(BoardKey.Back))
    }

    @Test
    fun fullscreenKeys() {
        fun fs(key: BoardKey) = com.milolab.btctv.ui.board.routeBoardKey(BoardMode.FULLSCREEN, key)
        assertEquals(BoardIntent.ChangeTf(-1), fs(BoardKey.Left))
        assertEquals(BoardIntent.ChangeTf(1), fs(BoardKey.Right))
        assertEquals(BoardIntent.OpenSettings, fs(BoardKey.Menu))
        assertEquals(BoardIntent.ExitFullscreen, fs(BoardKey.Back))
        assertEquals(BoardIntent.ExitFullscreen, fs(BoardKey.Ok))
        assertEquals(BoardIntent.None, fs(BoardKey.Up))
    }

    @Test
    fun searchAndSettingsConsumeKeys() {
        assertEquals(
            BoardIntent.CloseSearch,
            com.milolab.btctv.ui.board.routeBoardKey(BoardMode.SEARCH, BoardKey.Back)
        )
        assertEquals(
            BoardIntent.None,
            com.milolab.btctv.ui.board.routeBoardKey(BoardMode.SEARCH, BoardKey.Ok)
        )
        for (key in BoardKey.entries) {
            assertEquals(
                BoardIntent.None,
                com.milolab.btctv.ui.board.routeBoardKey(BoardMode.SETTINGS, key)
            )
        }
    }

    @Test
    fun timeframeSupportMatchesModuleKind() {
        assertTrue(moduleSupportsTimeframe(ModuleType.MainChart))
        assertTrue(moduleSupportsTimeframe(ModuleType.MiniChart))
        assertTrue(moduleSupportsTimeframe(ModuleType.PriceCard))
        assertFalse(moduleSupportsTimeframe(ModuleType.Watchlist))
        assertFalse(moduleSupportsTimeframe(ModuleType.Movers))
        assertFalse(moduleSupportsTimeframe(ModuleType.TickerStrip))
        assertFalse(moduleSupportsTimeframe(ModuleType.StatTile(StatKind.BTCD)))
    }
}
