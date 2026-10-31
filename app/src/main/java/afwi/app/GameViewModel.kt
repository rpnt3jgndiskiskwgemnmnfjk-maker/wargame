package afwi.app

import afwi.ai.Agent
import afwi.ai.Difficulty
import afwi.ai.HeuristicAgent
import afwi.engine.Action
import afwi.engine.Engine
import afwi.engine.GameState
import afwi.engine.Phase
import afwi.engine.Side
import afwi.engine.viewFor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class Screen { HOME, HANDOFF, PLAY, RESULT }

/** 対局の形式。CPU のときは人間側の陣営と難易度を持つ。 */
sealed interface Mode {
    data object Hotseat : Mode
    data class VsCpu(val humanSide: Side, val difficulty: Difficulty) : Mode {
        val cpuSide: Side get() = humanSide.opponent
    }
}

data class UiState(
    val screen: Screen = Screen.HOME,
    val mode: Mode = Mode.Hotseat,
    val game: GameState? = null,
    /** いま端末を持っている陣営。画面にはこの陣営の視点だけを描く。 */
    val holder: Side = Side.US,
    val selectedTokenId: String? = null,
    val cpuThinking: Boolean = false,
    val error: String? = null,
)

/** 対局の進行。ルールはすべて Engine に、CPU の判断は Agent に任せ、ここでは順番と画面だけを決める。 */
class GameViewModel : ViewModel() {
    val engine = Engine()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var cpuAgent: Agent? = null
    private var cpuJob: Job? = null

    fun newHotseatGame(campaignId: String) {
        cancelCpu()
        val game = engine.newGame(campaignId, Random.nextLong())
        _ui.value = UiState(screen = Screen.HANDOFF, mode = Mode.Hotseat, game = game, holder = engine.actors(game).first())
    }

    fun newCpuGame(campaignId: String, humanSide: Side, difficulty: Difficulty) {
        cancelCpu()
        val seed = Random.nextLong()
        cpuAgent = HeuristicAgent(engine, difficulty, seed)
        val game = engine.newGame(campaignId, seed)
        _ui.value = UiState(
            screen = Screen.PLAY, mode = Mode.VsCpu(humanSide, difficulty), game = game, holder = humanSide,
        )
        runCpuIfNeeded()
    }

    fun confirmHandoff() {
        _ui.value = _ui.value.copy(screen = Screen.PLAY)
    }

    fun select(tokenId: String?) {
        _ui.value = _ui.value.copy(selectedTokenId = tokenId)
    }

    fun act(action: Action) {
        val current = _ui.value
        if (current.cpuThinking) return
        val game = current.game ?: return
        val next = try {
            engine.apply(game, action)
        } catch (e: IllegalArgumentException) {
            _ui.value = current.copy(error = e.message)
            return
        }
        when (current.mode) {
            is Mode.Hotseat -> afterHotseatAction(current, next)
            is Mode.VsCpu -> {
                _ui.value = afterAction(current, next)
                runCpuIfNeeded()
            }
        }
    }

    private fun afterHotseatAction(current: UiState, next: GameState) {
        val actors = engine.actors(next)
        _ui.value = when {
            next.phase == Phase.GAME_OVER || current.holder in actors -> afterAction(current, next)
            // 次に行動する陣営へ端末を渡す（隠し情報を見せないため、受け渡し画面を挟む）
            else -> current.copy(
                screen = Screen.HANDOFF, game = next, holder = actors.first(), selectedTokenId = null, error = null,
            )
        }
    }

    private fun afterAction(current: UiState, next: GameState): UiState {
        val keepSelection = current.selectedTokenId?.takeIf { id ->
            next.tokens[id]?.let { it.isAirborne && it.owner == current.holder } == true
        }
        return if (next.phase == Phase.GAME_OVER) {
            current.copy(screen = Screen.RESULT, game = next, selectedTokenId = null, error = null, cpuThinking = false)
        } else {
            current.copy(game = next, selectedTokenId = keepSelection, error = null)
        }
    }

    /** CPU が行動する番なら、1手ずつ間を置いて進める（人間が盤面の変化を追えるように）。 */
    private fun runCpuIfNeeded() {
        val mode = _ui.value.mode as? Mode.VsCpu ?: return
        val agent = cpuAgent ?: return
        if (cpuJob?.isActive == true) return
        cpuJob = viewModelScope.launch {
            while (true) {
                val state = _ui.value
                val game = state.game ?: break
                if (game.phase == Phase.GAME_OVER || mode.cpuSide !in engine.actors(game)) break
                _ui.value = state.copy(cpuThinking = true)
                delay(CPU_STEP_DELAY_MS)
                // CPU には CPU 側の視点だけを渡す
                val view = engine.viewFor(game, mode.cpuSide)
                val action = withContext(Dispatchers.Default) { agent.choose(view) }
                val next = engine.apply(game, action)
                _ui.value = afterAction(_ui.value, next).copy(cpuThinking = next.phase != Phase.GAME_OVER)
            }
            _ui.value = _ui.value.copy(cpuThinking = false)
        }
    }

    private fun cancelCpu() {
        cpuJob?.cancel()
        cpuJob = null
        cpuAgent = null
    }

    fun backToHome() {
        cancelCpu()
        _ui.value = UiState()
    }

    private companion object {
        const val CPU_STEP_DELAY_MS = 600L
    }
}
