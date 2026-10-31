package afwi.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 支援カードの使用区分。 */
@Serializable
enum class UseType {
    /** キャンペーン中に1回。 */
    SINGLE,

    /** ATO ごとに1回。次の ATO ではまた選べる。 */
    MULTIPLE,
}

/** ATO の終わりまで続く効果（カードを使った陣営が持つ）。 */
@Serializable
enum class ModKind {
    OWN_AIR_TO_AIR_ADV,
    ENEMY_ACQUIRE_DIS,
    ENEMY_ATTACK_DIS,
    OWN_ATTACK_ADV,
    ENEMY_BASE_ATTACK_DIS,
    H6K_ADV,

    /** 次の空対空攻撃1回だけ有利。 */
    NEXT_AIR_TO_AIR_ADV,

    /** CL からの発進1回を全機発進にする。 */
    CL_MAX_GENERATION,

    /** 自軍の基地・CL は特殊作戦の攻撃を受けない。 */
    SOF_IMMUNE,
}

/** 自分の手番に使う支援カードの効果。少数の基本命令の組み合わせで68枚を表す。 */
@Serializable
sealed interface Effect {
    /** D4 を振り、出目の数だけ相手の駒を捕捉する。 */
    @Serializable @SerialName("acquireDice")
    data class AcquireDice(val cyberOn4: Boolean = false) : Effect

    /** サイバー値 × multiplier の数だけ相手の駒を捕捉する。 */
    @Serializable @SerialName("acquireCyber")
    data class AcquireCyber(val multiplier: Int = 1) : Effect

    @Serializable @SerialName("cyberAttempt")
    data object CyberAttempt : Effect

    @Serializable @SerialName("cyberDegrade")
    data class CyberDegrade(val amount: Int) : Effect

    /** サイバー値 × multiplier の数だけ、相手の上空の駒を盤上から除く（撃破ではない）。 */
    @Serializable @SerialName("removeTokens")
    data class RemoveTokens(val multiplier: Int = 1) : Effect

    /** サイバー値の数だけ、相手に手札の支援カードを捨てさせる。 */
    @Serializable @SerialName("forceDiscard")
    data object ForceDiscard : Effect

    /** サイバー値の数だけ、相手の上空の無人機を撃破する。 */
    @Serializable @SerialName("destroyUas")
    data object DestroyUas : Effect

    @Serializable @SerialName("modifier")
    data class Modifier(val mod: ModKind) : Effect

    /** 駒を発生させる。atBase なら自軍の基地上、そうでなければ任意の帯。 */
    @Serializable @SerialName("generate")
    data class Generate(val tokenType: String, val count: Int = 1, val atBase: Boolean = false) : Effect

    /**
     * 相手の基地への攻撃。hit が null なら命中判定なし（損害 D4 をそのまま与える）。
     * unblockable と sof はミサイル防衛・防空の影響を受けない。
     */
    @Serializable @SerialName("baseStrike")
    data class BaseStrike(val hit: Int? = null, val unblockable: Boolean = false, val sof: Boolean = false) : Effect

    /** 相手の水上艦1隻を自動で撃沈する。 */
    @Serializable @SerialName("destroyShip")
    data object DestroyShip : Effect

    /** 態勢の上限とは別に、飛行隊を1枚基地に追加する。 */
    @Serializable @SerialName("extraSquadron")
    data object ExtraSquadron : Effect

    /** 使用済みの支援カードを1枚手札に戻す（tag を指定するとそのタグのカードだけ）。 */
    @Serializable @SerialName("returnSpent")
    data class ReturnSpent(val tag: String? = null) : Effect

    /** 戦闘機の飛行隊1個の空対空射程を range にする（この ATO の間）。 */
    @Serializable @SerialName("airRangeUpgrade")
    data class AirRangeUpgrade(val range: Int) : Effect

    @Serializable @SerialName("choice")
    data class Choice(val options: List<Effect>, val labelsJa: List<String>) : Effect

    @Serializable @SerialName("sequence")
    data class Sequence(val steps: List<Effect>) : Effect
}

/** 応答カードを使える場面。 */
@Serializable
enum class Trigger {
    /** 相手が tag を持つカードを使った直後。 */
    ENEMY_CARD_TAG,

    /** 自軍の航空機が撃破された直後。 */
    OWN_AIRCRAFT_LOST,

    /** 自軍が対地攻撃を宣言した直後。 */
    OWN_SURFACE_STRIKE,

    /** 相手の攻撃が自軍に命中した直後、または相手がミサイル防衛を宣言した直後。 */
    ENEMY_HIT_OR_MD,

    /** 相手がミサイル防衛を宣言した直後。 */
    ENEMY_MD,

    /** 相手の攻撃で自軍の基地・CL に損害が出る直前。 */
    ENEMY_BASE_DAMAGE,

    /** 自軍の飛行隊が機体をすべて失った直後。 */
    OWN_SQUADRON_DEPLETED,

    /** 自軍の無人機の捕捉が失敗した直後。 */
    OWN_UAS_ACQUIRE_FAILED,
}

@Serializable
enum class ResponseEffect {
    CANCEL_CARD, RECOVER_AIRCRAFT, AUTO_HIT, CANCEL_HIT_OR_MD, CANCEL_MD, CANCEL_DAMAGE, REGENERATE_SQUADRON, REROLL_ACQUIRE,
}

@Serializable
data class ResponseDef(val trigger: Trigger, val effect: ResponseEffect, val tag: String? = null)

@Serializable
data class EnablerDef(
    val id: String,
    val side: Side,
    val nameJa: String,
    val family: String,
    val use: UseType,
    val tags: List<String>,
    val descriptionJa: String,
    /** 自分の手番に使うときの効果。null なら応答専用。 */
    val action: Effect? = null,
    /** 応答として使うときの条件と効果。 */
    val response: ResponseDef? = null,
)
