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

/** 出目 need 以上が出る確率（D4）。不利なら2個振って低い方。 */
fun successPercent(need: Int, disadvantage: Boolean = false): Int {
    val p = (5 - need).coerceIn(0, 4) / 4.0
    return ((if (disadvantage) p * p else p) * 100).toInt()
}

/** 相手の基地・CL への攻撃が不利になる理由（画面表示用）。 */
private fun defenseNote(v: PlayerView, base: afwi.engine.BaseKind): Pair<Boolean, String> {
    val reasons = v.baseDefense[v.opponent.side]?.get(base).orEmpty()
    return if (reasons.isEmpty()) false to "" else true to " 不利:${reasons.joinToString("・")}"
}

fun describe(a: Action, v: PlayerView, engine: afwi.engine.Engine): String = when (a) {
    is Action.ChoosePosture -> engine.content.posture(a.postureId).let {
        "${it.nameJa}(飛行隊${it.squadronLimit}・支援カード${it.enablerLimit}): ${it.descriptionJa}"
    }
    is Action.Draft -> "参加: " + a.squadronIds.joinToString("、") { squadronName(v, it) }
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
        val (dis, note) = if (target?.zone == afwi.engine.Zone.AtBase) defenseNote(v, afwi.engine.BaseKind.AIRBASE) else (false to "")
        "射撃: ${tokenName(v, a.targetId)} 命中${need}+(${successPercent(need, dis)}%)$note"
    }
    is Action.StrikeSquadron -> {
        val weapon = v.tokens.first { it.id == a.tokenId }.type?.surface
        val need = weapon?.hit ?: 0
        val base = v.squadrons.firstOrNull { it.id == a.squadronId }?.base ?: afwi.engine.BaseKind.AIRBASE
        val (dis, note) = defenseNote(v, base)
        val boom = if (weapon?.explosive == true) " 命中で損害D4" else ""
        "攻撃: ${squadronName(v, a.squadronId)}(${Ja.base(base)}) 命中${need}+(${successPercent(need, dis)}%)$boom$note"
    }
    is Action.StrikeAirbaseBlock -> {
        val need = v.tokens.first { it.id == a.tokenId }.type?.surface?.hit ?: 0
        val (dis, note) = defenseNote(v, afwi.engine.BaseKind.AIRBASE)
        "攻撃: ${Ja.side(v.opponent.side)}の基地施設 命中${need}+(${successPercent(need, dis)}%)$note"
    }
    is Action.EndTurn -> "手番を終える"
    is Action.Pass -> "パス(両者が続けてパスすると ATO 終了)"
}
