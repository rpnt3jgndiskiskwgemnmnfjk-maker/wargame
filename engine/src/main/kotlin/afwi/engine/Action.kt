package afwi.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** プレイヤー（人間または AI）がエンジンに渡す行動。帯の番号は米側基準。 */
@Serializable
sealed interface Action {
    val side: Side

    /** キャンペーンの最初に秘密のミッションを選ぶ。 */
    @Serializable @SerialName("mission")
    data class ChooseMission(override val side: Side, val mission: MissionId) : Action

    /** この ATO の支援カードを選ぶ（態勢の上限まで、非公開）。 */
    @Serializable @SerialName("enablers")
    data class ChooseEnablers(override val side: Side, val cardIds: Set<String>) : Action

    /** 手番中に支援カードを使う。option は選択式の効果の番号、target・zone は対象。 */
    @Serializable @SerialName("playEnabler")
    data class PlayEnabler(
        override val side: Side,
        val cardId: String,
        val option: Int = 0,
        val target: String? = null,
        val zone: Zone? = null,
    ) : Action

    /** 割り込みへの返答。カードか艦艇を選ぶ。どちらも null なら見送る。 */
    @Serializable @SerialName("respond")
    data class Respond(override val side: Side, val cardId: String? = null, val shipId: String? = null) : Action

    /** この ATO の態勢を選ぶ（両者が選んだら公開）。 */
    @Serializable @SerialName("posture")
    data class ChoosePosture(override val side: Side, val postureId: String) : Action

    /** この ATO に参加させる飛行隊を選ぶ。 */
    @Serializable @SerialName("draft")
    data class Draft(override val side: Side, val squadronIds: Set<String>) : Action

    /** 飛行隊の配置（基地か CL）を確定する。CL は米のみ。 */
    @Serializable @SerialName("deploy")
    data class Deploy(override val side: Side, val placements: Map<String, BaseKind>) : Action

    /** 主導権の入札。sacrifice は差し出す支援カード（1枚につき出目+1。山札に戻る）。 */
    @Serializable @SerialName("bid")
    data class SubmitBid(override val side: Side, val sacrifice: Set<String> = emptySet()) : Action

    /** 入札の勝者が先攻を決める。 */
    @Serializable @SerialName("chooseFirst")
    data class ChooseFirst(override val side: Side, val first: Side) : Action

    /** 飛行隊を起動（または帰投機を再発進）し、駒を to に発生させる。 */
    @Serializable @SerialName("activate")
    data class Activate(override val side: Side, val squadronId: String, val to: Zone) : Action

    @Serializable @SerialName("move")
    data class Move(override val side: Side, val tokenId: String, val to: Zone) : Action

    /** 捕捉。targetIds は試行回数（AEW は2）まで。同じ目標を2回狙ってもよい。 */
    @Serializable @SerialName("acquire")
    data class Acquire(override val side: Side, val tokenId: String, val targetIds: List<String>) : Action

    /** 駒（航空機・艦艇・基地上の ADA）への射撃。 */
    @Serializable @SerialName("shootToken")
    data class ShootToken(override val side: Side, val tokenId: String, val targetId: String) : Action

    /** 基地・CL 上の飛行隊カードへの攻撃。 */
    @Serializable @SerialName("strikeSquadron")
    data class StrikeSquadron(override val side: Side, val tokenId: String, val squadronId: String) : Action

    /** 敵の Airbase の追加損害ブロックへの攻撃（VP 目的）。 */
    @Serializable @SerialName("strikeBlock")
    data class StrikeAirbaseBlock(override val side: Side, val tokenId: String) : Action

    /** 何か行動した後に手番を終える。 */
    @Serializable @SerialName("endTurn")
    data class EndTurn(override val side: Side) : Action

    /** 何もせずに手番を終える。両者が続けてパスすると ATO が終わる。 */
    @Serializable @SerialName("pass")
    data class Pass(override val side: Side) : Action
}
