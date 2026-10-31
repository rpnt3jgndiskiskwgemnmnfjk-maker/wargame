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
enum class Phase { MISSION, POSTURE, DRAFT, ENABLERS, DEPLOY, BID, CHOOSE_ORDER, TURNS, GAME_OVER }

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
    /** この ATO に参加している（ドラフトされ、盤上の基地か CL に置かれている）。 */
    val drafted: Boolean = false,
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
    val tokenId: String? = null,
    val typeId: String? = null,
    val vpClass: VpClass? = null,
    val onGround: Boolean = false,
    val inAir: Boolean = false,
)

@Serializable
data class PlayerState(
    val side: Side,
    /** 秘密のミッション。選ぶ前は null。 */
    val mission: MissionId? = null,
    /** この ATO の態勢（公開）。 */
    val posture: String? = null,
    val usedPostures: List<String> = emptyList(),
    val drafted: Boolean = false,
    val enablersChosen: Boolean = false,
    val deployed: Boolean = false,
    val bid: Int? = null,
    /** この ATO の支援カードの手札（非公開）。 */
    val hand: List<String> = emptyList(),
    /** 使用済みの単発カード（キャンペーン中は選べない）。 */
    val spent: List<String> = emptyList(),
    /** 情報判定で見た相手の手札。 */
    val knownEnemyCards: List<String> = emptyList(),
    /** この ATO の間続く効果。 */
    val modifiers: List<ModKind> = emptyList(),
    /** 空対空射程を変えた飛行隊（弾薬改良）。 */
    val airRange: Map<String, Int> = emptyMap(),
    /** 使ったカード（打ち消されなかったもの）。ミッションの採点に使う。 */
    val cardPlays: List<CardPlay> = emptyList(),
    /** 飛行隊を起動した ATO（法の執行の採点用）。 */
    val activations: List<Int> = emptyList(),
    /** 支援カードで駒を発生させた ATO（法の執行の採点用）。 */
    val enablerTokens: List<Int> = emptyList(),
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

@Serializable
data class CardPlay(val ato: Int, val cardId: String)

/** 割り込みの種類。 */
@Serializable
enum class WindowKind {
    CARD_CANCEL, AUTO_HIT, MISSILE_DEFENSE, MD_COUNTER, HIT_CANCEL, DAMAGE_CANCEL,
    AIRCRAFT_LOST, SQUADRON_DEPLETED, UAS_REROLL,
}

@Serializable
enum class AttackKind { TOKEN, SQUADRON, BLOCK, CARD_BASE }

/** 進行中の攻撃。割り込みで中断しても、ここから続きを解決できる。 */
@Serializable
data class AttackCtx(
    val attacker: Side,
    val kind: AttackKind,
    val shooterId: String? = null,
    val targetTokenId: String? = null,
    val targetSquadronId: String? = null,
    val cardId: String? = null,
    /** 命中に必要な出目。null なら命中判定なし。 */
    val hit: Int? = null,
    val explosive: Boolean = false,
    val surface: Boolean = false,
    val unblockable: Boolean = false,
    val sof: Boolean = false,
    val autoHitChecked: Boolean = false,
    val autoHit: Boolean = false,
    val mdChecked: Boolean = false,
    val md: Boolean = false,
    val counterChecked: Boolean = false,
    val rolled: Boolean = false,
    val hitResult: Boolean = false,
    val natural: Int? = null,
    val hitCancelChecked: Boolean = false,
    val damage: Int? = null,
    val damageCancelChecked: Boolean = false,
)

/** 使用中のカード（打ち消しの割り込みを待つ間、選んだ対象を覚えておく）。 */
@Serializable
data class CardPlayCtx(
    val side: Side,
    val cardId: String,
    val option: Int = 0,
    val target: String? = null,
    val zone: Zone? = null,
)

/** 割り込みで、ある陣営の判断を待っている状態。 */
@Serializable
data class Pending(
    val kind: WindowKind,
    val decider: Side,
    val cardOptions: List<String> = emptyList(),
    val shipOptions: List<String> = emptyList(),
    val play: CardPlayCtx? = null,
    val tokenId: String? = null,
    val squadronId: String? = null,
    /** 振り直しの割り込みで、捕捉を試みた駒。 */
    val seekerId: String? = null,
    val promptJa: String,
)

/** 攻撃の解決後に、応答の機会を与える損失。 */
@Serializable
data class LossEvent(val kind: WindowKind, val owner: Side, val tokenId: String? = null, val squadronId: String? = null)

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
    /** ATO ごとの主導権を持った陣営（大会の交代ルールで使う）。 */
    val initiativeHistory: List<Side> = emptyList(),
    val pending: Pending? = null,
    val attack: AttackCtx? = null,
    val lossQueue: List<LossEvent> = emptyList(),
    /** GAME_OVER のときの勝者。null で GAME_OVER なら引き分け。 */
    val winner: Side? = null,
    val gameOverReason: String? = null,
    val rngState: Long,
    val log: List<GameEvent> = emptyList(),
) {
    fun player(side: Side): PlayerState = players.getValue(side)
}
