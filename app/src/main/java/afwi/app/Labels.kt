package afwi.app

import afwi.engine.Action
import afwi.engine.Ja
import afwi.engine.PlayerView
import afwi.engine.TokenView
import androidx.compose.ui.graphics.Color

val UsColor = Color(0xFF1E5AA8)
val PrcColor = Color(0xFFB3261E)

fun sideColor(side: afwi.engine.Side): Color = if (side == afwi.engine.Side.US) UsColor else PrcColor

/** 駒の短い表示名。正体が分からない相手の駒は「?」。 */
fun tokenShortName(t: TokenView): String = if (t.masked) "?" else t.type!!.id

fun tokenName(v: PlayerView, id: String): String {
    val t = v.tokens.firstOrNull { it.id == id } ?: return "不明な駒"
    val name = if (t.masked) "未確認機" else "${Ja.side(t.owner)}${t.type!!.nameJa}"
    return "$name(${Ja.zone(t.zone, v.viewer)})"
}

fun squadronName(v: PlayerView, id: String): String {
    val sq = v.squadrons.firstOrNull { it.id == id } ?: return "不明な飛行隊"
    val name = sq.def?.let { def ->
        val type = def.tokenType
        "${def.nameJa}($type)"
    } ?: "伏せた飛行隊"
    return "${Ja.side(sq.owner)}の$name"
}

/** 出目 need 以上が出る確率（D4、修正なし）。 */
fun successPercent(need: Int): Int = ((5 - need).coerceIn(0, 4) * 25)

fun describe(a: Action, v: PlayerView): String = when (a) {
    is Action.Deploy -> a.placements.entries.joinToString("、") { (id, base) -> "${squadronName(v, id)} → ${Ja.base(base)}" }
    is Action.SubmitBid -> "入札する(差し出す支援カード ${a.sacrifice}枚)"
    is Action.ChooseFirst -> "${Ja.side(a.first)}を先攻にする"
    is Action.Activate -> "起動: ${squadronName(v, a.squadronId)} → ${Ja.zone(a.to, v.viewer)}"
    is Action.Move -> "移動 → ${Ja.zone(a.to, v.viewer)}"
    is Action.Acquire -> {
        val seeker = v.tokens.first { it.id == a.tokenId }
        val bonus = seeker.type?.acqBonus ?: 0
        "捕捉: " + a.targetIds.joinToString(" と ") { id ->
            val need = v.tokens.firstOrNull { it.id == id }?.acqValue ?: 0
            "${tokenName(v, id)} 必要${need}+(${successPercent(need - bonus)}%)"
        }
    }
    is Action.ShootToken -> {
        val shooter = v.tokens.first { it.id == a.tokenId }
        val target = v.tokens.firstOrNull { it.id == a.targetId }
        val weapon = if (target?.type?.cls?.isAircraft == true) shooter.type?.air else shooter.type?.surface
        val need = weapon?.hit ?: 0
        "射撃: ${tokenName(v, a.targetId)} 命中${need}+(${successPercent(need)}%)"
    }
    is Action.StrikeSquadron -> {
        val need = v.tokens.first { it.id == a.tokenId }.type?.surface?.hit ?: 0
        "攻撃: ${squadronName(v, a.squadronId)} 命中${need}+(${successPercent(need)}%)"
    }
    is Action.StrikeAirbaseBlock -> {
        val need = v.tokens.first { it.id == a.tokenId }.type?.surface?.hit ?: 0
        "攻撃: ${Ja.side(v.opponent.side)}の基地施設 命中${need}+(${successPercent(need)}%)"
    }
    is Action.EndTurn -> "手番を終える"
    is Action.Pass -> "パス(両者が続けてパスすると ATO 終了)"
}
