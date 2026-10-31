package afwi.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 駒の居場所。帯の番号は米側基準（米の基地エリア=0、帯1〜5、中の基地エリア=6）。 */
@Serializable
sealed interface Zone {
    /** 距離帯。pos は米側基準の 1〜5。 */
    @Serializable @SerialName("band")
    data class Band(val pos: Int) : Zone

    /** 陣営の Standoff 区域。 */
    @Serializable @SerialName("standoff")
    data class Standoff(val side: Side) : Zone

    /** 飛行隊カードの上（未発進・帰投済み）。 */
    @Serializable @SerialName("grounded")
    data object Grounded : Zone

    /** 基地上に展開した地上部隊（ADA）。 */
    @Serializable @SerialName("atBase")
    data object AtBase : Zone

    /** 盤外（弾切れで退いた艦艇など）。 */
    @Serializable @SerialName("offboard")
    data object Offboard : Zone

    /** 撃破済み。 */
    @Serializable @SerialName("destroyed")
    data object Destroyed : Zone
}

@Serializable
enum class BaseKind { AIRBASE, CL }

@Serializable
enum class IntelLevel { NORMAL, ADVANTAGE }

@Serializable
enum class Phase { DEPLOY, BID, CHOOSE_ORDER, TURNS, GAME_OVER }

@Serializable
data class TokenState(
    val id: String,
    val typeId: String,
    val owner: Side,
    val squadronId: String?,
    val zone: Zone = Zone.Grounded,
    val acquired: Boolean = false,
    val winchester: Boolean = false,
    /** CL からの発進判定で発進できず、この ATO では使えない。 */
    val unavailable: Boolean = false,
    val salvosUsed: Int = 0,
    val damage: Int = 0,
) {
    val isAirborne: Boolean get() = zone is Zone.Band || zone is Zone.Standoff
    val isDestroyed: Boolean get() = zone == Zone.Destroyed
}

@Serializable
data class SquadronState(
    val id: String,
    val defId: String,
    val owner: Side,
    val base: BaseKind = BaseKind.AIRBASE,
    /** 起動済み（表向き）。 */
    val faceUp: Boolean = false,
    val damage: Int = 0,
) {
    val isDestroyed: Boolean get() = damage >= SQUADRON_HP

    companion object {
        const val SQUADRON_HP = 2
    }
}

@Serializable
enum class CaptureKind { TOKEN, SQUADRON, AIRBASE_BLOCK }

/** 撃破した側が保管する記録（VP 計上用）。 */
@Serializable
data class CaptureRecord(
    val kind: CaptureKind,
    val ato: Int,
    val typeId: String? = null,
    val vpClass: VpClass? = null,
    val onGround: Boolean = false,
    val inAir: Boolean = false,
)

@Serializable
data class PlayerState(
    val side: Side,
    val mission: MissionId,
    val deployed: Boolean = false,
    val bid: Int? = null,
    val cyber: Int = 1,
    val intel: IntelLevel = IntelLevel.NORMAL,
    /** 自分の基地の追加損害ブロックのうち、撃たれた数。 */
    val airbaseBlocksHit: Int = 0,
    val captured: List<CaptureRecord> = emptyList(),
    val vp: Int = 0,
)

@Serializable
data class TurnState(
    val active: Side,
    val consecutivePasses: Int = 0,
    val activated: Boolean = false,
    val moved: Boolean = false,
    val acquired: Boolean = false,
    val shot: Boolean = false,
    val enablerUsed: Boolean = false,
) {
    /** 飛行隊の起動か MAS のいずれかを行った（どちらか一方しかできない）。 */
    val primaryUsed: Boolean get() = activated || moved || acquired || shot
    val didSomething: Boolean get() = primaryUsed || enablerUsed
}

/** 履歴の1件。見え方が陣営で違うため、陣営ごとの文を持つ（その陣営に見えないときは null）。 */
@Serializable
data class GameEvent(val texts: Map<Side, String>) {
    fun textFor(side: Side): String? = texts[side]
}

@Serializable
data class GameState(
    val campaignId: String,
    val ato: Int,
    val atoCount: Int,
    val phase: Phase,
    val players: Map<Side, PlayerState>,
    val tokens: Map<String, TokenState>,
    val squadrons: Map<String, SquadronState>,
    val turn: TurnState,
    val initiativeWinner: Side? = null,
    /** GAME_OVER のときの勝者。null で GAME_OVER なら引き分け。 */
    val winner: Side? = null,
    val gameOverReason: String? = null,
    val rngState: Long,
    val log: List<GameEvent> = emptyList(),
) {
    fun player(side: Side): PlayerState = players.getValue(side)
}
