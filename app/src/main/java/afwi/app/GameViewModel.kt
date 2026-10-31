package afwi.app

import afwi.ai.Agent
import afwi.ai.Difficulty
import afwi.ai.HeuristicAgent
import afwi.engine.Action
import afwi.engine.Content
import afwi.engine.Engine
import afwi.engine.GameRecord
import afwi.engine.GameState
import afwi.engine.replay
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

enum class Screen { HOME, SETTINGS, RULES, HANDOFF, PLAY, RESULT, REPLAY }

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
    /** リプレイできる前回の対局がある。 */
    val hasLastGame: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** リプレイ中の各手の後の状態と、いま見ている手の番号。 */
    val replayStates: List<GameState> = emptyList(),
    val replayActions: List<Action> = emptyList(),
    val replayIndex: Int = 0,
    val replayViewer: Side = Side.US,
)

/**
 * 対局の進行。ルールはすべて Engine に、CPU の判断は Agent に任せ、ここでは順番・画面・保存だけを決める。
 * 行動を1つ確定するたびに対局を端末内に自動保存し、アプリを閉じても続きから遊べるようにする。
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val content = Content.loadBundled()
    private val settingsStore = SettingsStore(app)
    private val saveFile = File(app.filesDir, "save.json")
    private val lastGameFile = File(app.filesDir, "lastgame.json")

    /** 対局の記録（リプレイ用）。行動を確定するたびに足していく。 */
    private var record: GameRecord? = null

    /** 対局ごとに、そのときのルール設定で作り直す。 */
    var engine = Engine(content)
        private set

    private val _ui = MutableStateFlow(
        UiState(hasSave = loadSave() != null, hasLastGame = lastGameFile.exists(), settings = settingsStore.load()),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var cpuAgent: Agent? = null
    private var cpuJob: Job? = null

    fun newHotseatGame(campaignId: String) {
        cancelCpu()
        engine = Engine(content, _ui.value.settings.toRules())
        val seed = Random.nextLong()
        val game = engine.newGame(campaignId, seed)
        record = GameRecord(campaignId, seed)
        _ui.value = UiState(
            screen = Screen.HANDOFF, mode = Mode.Hotseat, game = game, holder = engine.actors(game).first(),
            settings = _ui.value.settings, hasLastGame = _ui.value.hasLastGame,
        )
        persist()
    }

    fun newCpuGame(campaignId: String, humanSide: Side, difficulty: Difficulty) {
        cancelCpu()
        engine = Engine(content, _ui.value.settings.toRules())
        val seed = Random.nextLong()
        cpuAgent = HeuristicAgent(engine, difficulty, seed)
        val game = engine.newGame(campaignId, seed)
        record = GameRecord(campaignId, seed)
        _ui.value = UiState(
            screen = Screen.PLAY, mode = Mode.VsCpu(humanSide, difficulty), game = game, holder = humanSide,
            settings = _ui.value.settings, hasLastGame = _ui.value.hasLastGame,
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
        record = saved.record
        val mode = if (saved.meta["mode"] == "cpu") {
            val human = Side.valueOf(saved.meta["human"] ?: "US")
            val difficulty = Difficulty.valueOf(saved.meta["difficulty"] ?: "NORMAL")
            cpuAgent = HeuristicAgent(engine, difficulty, Random.nextLong())
            Mode.VsCpu(human, difficulty)
        } else {
            Mode.Hotseat
        }
        _ui.value = when (mode) {
            is Mode.VsCpu -> UiState(
                screen = Screen.PLAY, mode = mode, game = game, holder = mode.humanSide,
                settings = _ui.value.settings, hasLastGame = _ui.value.hasLastGame,
            )
            // ホットシートは、隠し情報を見せないよう受け渡し画面から始める
            is Mode.Hotseat -> UiState(
                screen = Screen.HANDOFF, mode = mode, game = game,
                holder = engine.actors(game).firstOrNull() ?: Side.US, settings = _ui.value.settings,
                hasLastGame = _ui.value.hasLastGame,
            )
        }
        runCpuIfNeeded()
    }

    fun openSettings() {
        _ui.value = _ui.value.copy(screen = Screen.SETTINGS)
    }

    fun openRules() {
        _ui.value = _ui.value.copy(screen = Screen.RULES)
    }

    /** 前回終わった対局を、記録から1手ずつ再現して見返す。 */
    fun openReplay() {
        val current = _ui.value
        // 結果画面から開いたときは、手元の記録をそのまま使う（ファイルの書き込みを待たない）
        val inMemory = record?.takeIf { current.game?.phase == Phase.GAME_OVER }
        val saved = if (inMemory == null) {
            runCatching { lastGameFile.readText() }.getOrNull()?.let { SavedGame.decodeOrNull(it, content) }
        } else null
        val rec = inMemory ?: saved?.record
        if (rec == null) {
            _ui.value = current.copy(hasLastGame = false, error = "リプレイできる対局がありません")
            return
        }
        val rules = saved?.rules ?: engine.rules
        val viewer = when (val m = current.mode) {
            is Mode.VsCpu -> if (inMemory != null) m.humanSide else null
            else -> null
        } ?: saved?.meta?.get("human")?.let { Side.valueOf(it) } ?: Side.US
        viewModelScope.launch {
            val replayEngine = Engine(content, rules)
            val states = withContext(Dispatchers.Default) { runCatching { replayEngine.replay(rec) }.getOrNull() }
            if (states == null) {
                _ui.value = _ui.value.copy(error = "記録を再現できませんでした")
                return@launch
            }
            engine = replayEngine
            _ui.value = _ui.value.copy(
                screen = Screen.REPLAY, replayStates = states, replayActions = rec.actions,
                replayIndex = states.lastIndex, replayViewer = viewer,
            )
        }
    }

    fun replayStep(index: Int) {
        val st = _ui.value
        _ui.value = st.copy(replayIndex = index.coerceIn(0, st.replayStates.lastIndex))
    }

    fun replayViewer(side: Side) {
        _ui.value = _ui.value.copy(replayViewer = side)
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
        record = record?.plus(action)
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
                record = record?.plus(action)
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
        val meta = when (val m = state.mode) {
            is Mode.Hotseat -> mapOf("mode" to "hotseat")
            is Mode.VsCpu -> mapOf("mode" to "cpu", "human" to m.humanSide.name, "difficulty" to m.difficulty.name)
        }
        val over = game.phase == Phase.GAME_OVER
        val text = if (over) null else engine.save(game, meta, record)
        // 終わった対局は、リプレイ用に別のファイルへ残す
        val lastGame = if (over && record != null) engine.save(game, meta, record) else null
        _ui.value = _ui.value.copy(hasSave = text != null, hasLastGame = _ui.value.hasLastGame || lastGame != null)
        if (lastGame != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { lastGameFile.writeText(lastGame) } }
        }
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
        _ui.value = UiState(
            hasSave = loadSave() != null || _ui.value.hasSave,
            hasLastGame = lastGameFile.exists() || _ui.value.hasLastGame,
            settings = _ui.value.settings,
        )
    }
}
