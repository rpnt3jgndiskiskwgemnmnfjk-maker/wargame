package afwi.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 保存データ。対局の状態そのものと、そのとき使っていたルール設定を持つ。
 * meta は画面側の情報（CPU 対戦か、人間の陣営、難易度など）を文字列で入れる。
 */
@Serializable
data class SavedGame(
    val format: Int = FORMAT,
    val contentVersion: Int,
    val rules: RuleSettings,
    val state: GameState,
    val meta: Map<String, String> = emptyMap(),
    /** リプレイ用の対局記録。 */
    val record: GameRecord? = null,
) {
    companion object {
        /** 保存形式の版。互換性のない変更をしたら上げる。 */
        const val FORMAT = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(save: SavedGame): String = json.encodeToString(serializer(), save)

        /** 読めない、または版が合わないときは null。 */
        fun decodeOrNull(text: String, content: Content): SavedGame? = runCatching {
            json.decodeFromString(serializer(), text)
        }.getOrNull()?.takeIf { it.format == FORMAT && it.contentVersion == content.version }
    }
}

fun Engine.save(state: GameState, meta: Map<String, String> = emptyMap(), record: GameRecord? = null): String =
    SavedGame.encode(SavedGame(contentVersion = content.version, rules = rules, state = state, meta = meta, record = record))

/** 対局の記録。エンジンは決定的なので、キャンペーン・種・行動列があれば対局を完全に再現できる。 */
@Serializable
data class GameRecord(
    val campaignId: String,
    val seed: Long,
    val actions: List<Action> = emptyList(),
) {
    fun plus(action: Action): GameRecord = copy(actions = actions + action)
}

/** 記録から、各行動の後の状態を順に作る（先頭は対局開始時の状態）。 */
fun Engine.replay(record: GameRecord): List<GameState> {
    val states = ArrayList<GameState>(record.actions.size + 1)
    var s = newGame(record.campaignId, record.seed)
    states += s
    for (a in record.actions) {
        s = apply(s, a)
        states += s
    }
    return states
}
