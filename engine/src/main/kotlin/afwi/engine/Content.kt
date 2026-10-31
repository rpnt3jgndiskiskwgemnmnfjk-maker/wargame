package afwi.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class Side {
    US, PRC;

    val opponent: Side get() = if (this == US) PRC else US

    /** 米側基準の座標で、この陣営の基地エリア（Airbase・CL・Standoff）の位置。 */
    val baseCoord: Int get() = if (this == US) 0 else 6

    /** この陣営から見た n 帯を、米側基準の帯番号に直す。 */
    fun bandToUsFrame(ownBand: Int): Int = if (this == US) ownBand else 6 - ownBand

    /** 米側基準の帯番号を、この陣営から見た帯番号に直す。 */
    fun bandFromUsFrame(usBand: Int): Int = if (this == US) usBand else 6 - usBand
}

@Serializable
enum class TokenClass {
    FIGHTER_GEN4, FIGHTER_GEN5, BOMBER, AEW, EW, UAS_ATTACK, UAS_RECON, ADA, SHIP;

    val isAircraft: Boolean get() = this != ADA && this != SHIP

    /** 爆撃機・AEW・EW は自陣の Standoff に置ける。 */
    val canUseStandoff: Boolean get() = this == BOMBER || this == AEW || this == EW
}

@Serializable
enum class WinchesterType { STANDARD, INFINITE, NONE, SALVO }

@Serializable
enum class VpClass { SHIP, BOMBER, ADA, AEW, OTHER }

@Serializable
enum class Generate { BAND1, ANY_BAND, BAND1_OR_STANDOFF, AIRBASE }

@Serializable
data class Weapon(val range: Int, val hit: Int, val explosive: Boolean = false)

@Serializable
data class TokenType(
    val id: String,
    val nameJa: String,
    val side: Side,
    val cls: TokenClass,
    val move: Int,
    val acqRange: Int,
    val acqTries: Int = 1,
    val acqBonus: Int = 0,
    /** 探知値：この駒を捕捉するのに必要な出目。 */
    val acqValue: Int,
    val air: Weapon? = null,
    val surface: Weapon? = null,
    val winchester: WinchesterType,
    val salvos: Int? = null,
    val wez: Int? = null,
    val vpClass: VpClass,
)

@Serializable
data class SquadronDef(
    val id: String,
    val side: Side,
    val nameJa: String,
    val tokenType: String,
    val count: Int,
    val generate: Generate,
    val flying: Boolean = true,
)

@Serializable
enum class MissionId {
    ATTRITION, INTERDICTION, ECONOMY_OF_FORCE, ENFORCE_RULE_OF_LAW,
    NON_KINETIC_DOMINANCE, COUNTER_INTERVENTION, THREE_DOMINANCES,
}

@Serializable
data class CampaignDef(
    val id: String,
    val nameJa: String,
    val descriptionJa: String,
    val atoCount: Int,
    val mission: MissionId,
    val fixedSquadrons: Map<Side, List<String>>,
)

@Serializable
data class Content(
    val version: Int,
    val tokenTypes: List<TokenType>,
    val squadrons: List<SquadronDef>,
    val campaigns: List<CampaignDef>,
) {
    private val tokenTypeById by lazy { tokenTypes.associateBy { it.id } }
    private val squadronById by lazy { squadrons.associateBy { it.id } }
    private val campaignById by lazy { campaigns.associateBy { it.id } }

    fun tokenType(id: String): TokenType = tokenTypeById[id] ?: error("unknown token type: $id")
    fun squadron(id: String): SquadronDef = squadronById[id] ?: error("unknown squadron: $id")
    fun campaign(id: String): CampaignDef = campaignById[id] ?: error("unknown campaign: $id")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Content = json.decodeFromString(serializer(), text)

        /** エンジンに同梱した afwi/content.json を読む。 */
        fun loadBundled(): Content {
            val stream = Content::class.java.classLoader?.getResourceAsStream("afwi/content.json")
                ?: error("afwi/content.json not found on classpath")
            return stream.bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
        }
    }
}

/** 情報源で決まらないルールの設定値（仕様書 §14、設計書 §8）。 */
@Serializable
data class RuleSettings(
    /** 基地の追加損害ブロックの数。 */
    val airbaseBlocks: Int = 3,
    /** 基地の追加損害ブロック1つの VP（未確認のため仮値）。 */
    val airbaseBlockVp: Int = 1,
    /** 弾切れで帰投した機体を再補給し、同じ ATO 中に再発進できるか（解釈）。 */
    val rtbRearm: Boolean = true,
    /** 艦艇の耐久（未確認のため仮値）。 */
    val shipHp: Int = 1,
)
