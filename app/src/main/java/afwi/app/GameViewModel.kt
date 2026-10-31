package afwi.app

import afwi.ai.Agent
import afwi.ai.Difficulty
import afwi.ai.HeuristicAgent
import afwi.engine.Action
import afwi.engine.Content
import afwi.engine.Engine
import afwi.engine.GameState
import afwi.engine.Phase
import afwi.engine.SavedGame
import afwi.engine.Side
import afwi.engine.save
import afwi.engine.viewFor
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

enum class Screen { HOME, SETTINGS, HANDOFF, PLAY, RESULT }

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
    /** 続きから再開できる保存データがある。 */
    val hasSave: Boolean = false,
    val settings: AppSettings = AppSettings(),
)

/**
 * 対局の進行。ルールはすべて Engine に、CPU の判断は Agent に任せ、ここでは順番・画面・保存だけを決める。
 * 行動を1つ確定するたびに対局を端末内に自動保存し、アプリを閉じても続きから遊べるようにする。
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val content = Content.loadBundled()
    private val settingsStore = SettingsStore(app)
    private val saveFile = File(app.filesDir, "save.json")

    /** 対局ごとに、そのときのルール設定で作り直す。 */
    var engine = Engine(content)
        private set

    private val _ui = MutableStateFlow(UiState(hasSave = loadSave() != null, settings = settingsStore.load()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var cpuAgent: Agent? = null
    private var cpuJob: Job? = null

    fun newHotseatGame(campaignId: String) {
        cancelCpu()
        engine = Engine(content, _ui.value.settings.toRules())
        val game = engine.newGame(campaignId, Random.nextLong())
        _ui.value = UiState(
            screen = Screen.HANDOFF, mode = Mode.Hotseat, game = game, holder = engine.actors(game).first(),
            settings = _ui.value.settings,
        )
        persist()
    }

    fun newCpuGame(campaignId: String, humanSide: Side, difficulty: Difficulty) {
        cancelCpu()
        engine = Engine(content, _ui.value.settings.toRules())
        val seed = Random.nextLong()
        cpuAgent = HeuristicAgent(engine, difficulty, seed)
        val game = engine.newGame(campaignId, seed)
        _ui.value = UiState(
            screen = Screen.PLAY, mode = Mode.VsCpu(humanSide, difficulty), game = game, holder = humanSide,
            settings = _ui.value.settings,
        )
        persist()
        runCpuIfNeeded()
    }

    /** 保存データから続きを始める。 */
    fun resume() {
        val saved = loadSave() ?: run {
            _ui.value = _ui.value.copy(hasSave = false)
            return
        }
        cancelCpu()
        engine = Engine(content, saved.rules)
        val game = saved.state
        val mode = if (saved.meta["mode"] == "cpu") {
            val human = Side.valueOf(saved.meta["human"] ?: "US")
            val difficulty = Difficulty.valueOf(saved.meta["difficulty"] ?: "NORMAL")
            cpuAgent = HeuristicAgent(engine, difficulty, Random.nextLong())
            Mode.VsCpu(human, difficulty)
        } else {
            Mode.Hotseat
        }
        _ui.value = when (mode) {
            is Mode.VsCpu -> UiState(screen = Screen.PLAY, mode = mode, game = game, holder = mode.humanSide, settings = _ui.value.settings)
            // ホットシートは、隠し情報を見せないよう受け渡し画面から始める
            is Mode.Hotseat -> UiState(
                screen = Screen.HANDOFF, mode = mode, game = game,
                holder = engine.actors(game).firstOrNull() ?: Side.US, settings = _ui.value.settings,
            )
        }
        runCpuIfNeeded()
    }

    fun openSettings() {
        _ui.value = _ui.value.copy(screen = Screen.SETTINGS)
    }

    fun updateSettings(settings: AppSettings) {
        settingsStore.save(settings)
        _ui.value = _ui.value.copy(settings = settings)
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
        persist()
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
                delay(state.settings.cpuDelayMs)
                // CPU には CPU 側の視点だけを渡す
                val view = engine.viewFor(game, mode.cpuSide)
                val action = withContext(Dispatchers.Default) { agent.choose(view) }
                val next = engine.apply(game, action)
                _ui.value = afterAction(_ui.value, next).copy(cpuThinking = next.phase != Phase.GAME_OVER)
                persist()
            }
            _ui.value = _ui.value.copy(cpuThinking = false)
        }
    }

    // ---- 保存 ----

    private fun loadSave(): SavedGame? =
        runCatching { saveFile.readText() }.getOrNull()?.let { SavedGame.decodeOrNull(it, content) }

    /** 対局中なら保存し、終わっていれば保存データを消す。 */
    private fun persist() {
        val state = _ui.value
        val game = state.game ?: return
        val text = if (game.phase == Phase.GAME_OVER) {
            null
        } else {
            val meta = when (val m = state.mode) {
                is Mode.Hotseat -> mapOf("mode" to "hotseat")
                is Mode.VsCpu -> mapOf("mode" to "cpu", "human" to m.humanSide.name, "difficulty" to m.difficulty.name)
            }
            engine.save(game, meta)
        }
        _ui.value = state.copy(hasSave = text != null)
        val seq = ++saveSeq
        viewModelScope.launch(Dispatchers.IO) {
            // 書き込みが前後しても、最後に確定した状態だけを残す
            synchronized(saveLock) {
                if (seq != saveSeq) return@synchronized
                runCatching { if (text == null) saveFile.delete() else saveFile.writeText(text) }
            }
        }
    }

    private val saveLock = Any()

    @Volatile
    private var saveSeq = 0

    private fun cancelCpu() {
        cpuJob?.cancel()
        cpuJob = null
        cpuAgent = null
    }

    fun backToHome() {
        cancelCpu()
        _ui.value = UiState(hasSave = loadSave() != null || _ui.value.hasSave, settings = _ui.value.settings)
    }
}
