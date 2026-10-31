package afwi.app

import afwi.engine.Action
import afwi.engine.Engine
import afwi.engine.GameState
import afwi.engine.Phase
import afwi.engine.Side
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

enum class Screen { HOME, HANDOFF, PLAY, RESULT }

data class UiState(
    val screen: Screen = Screen.HOME,
    val game: GameState? = null,
    /** いま端末を持っている陣営。画面にはこの陣営の視点だけを描く。 */
    val holder: Side = Side.US,
    val selectedTokenId: String? = null,
    val error: String? = null,
)

/** ホットシート対局の進行。ルールはすべて Engine に任せ、ここでは端末の受け渡しだけを決める。 */
class GameViewModel : ViewModel() {
    val engine = Engine()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun newHotseatGame(campaignId: String) {
        val game = engine.newGame(campaignId, Random.nextLong())
        _ui.value = UiState(screen = Screen.HANDOFF, game = game, holder = engine.actors(game).first())
    }

    fun confirmHandoff() {
        _ui.value = _ui.value.copy(screen = Screen.PLAY)
    }

    fun select(tokenId: String?) {
        _ui.value = _ui.value.copy(selectedTokenId = tokenId)
    }

    fun act(action: Action) {
        val current = _ui.value
        val game = current.game ?: return
        val next = try {
            engine.apply(game, action)
        } catch (e: IllegalArgumentException) {
            _ui.value = current.copy(error = e.message)
            return
        }
        val actors = engine.actors(next)
        val keepSelection = current.selectedTokenId?.takeIf { id ->
            next.tokens[id]?.let { it.isAirborne && it.owner == current.holder } == true
        }
        _ui.value = when {
            next.phase == Phase.GAME_OVER -> current.copy(screen = Screen.RESULT, game = next, selectedTokenId = null, error = null)
            current.holder in actors -> current.copy(game = next, selectedTokenId = keepSelection, error = null)
            // 次に行動する陣営へ端末を渡す（隠し情報を見せないため、受け渡し画面を挟む）
            else -> current.copy(
                screen = Screen.HANDOFF, game = next, holder = actors.first(), selectedTokenId = null, error = null,
            )
        }
    }

    fun backToHome() {
        _ui.value = UiState()
    }
}
