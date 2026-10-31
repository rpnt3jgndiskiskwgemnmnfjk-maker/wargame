package afwi.app

import afwi.engine.Action
import afwi.engine.BaseKind
import afwi.engine.Ja
import afwi.engine.PlayerView
import afwi.engine.Side
import afwi.engine.TokenView
import afwi.engine.Zone
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 盤面。相手の基地を上、自分の基地を下にし、帯は自分から数えた番号で描く。 */
@Composable
fun Board(view: PlayerView, selectedTokenId: String?, onSelect: (String?) -> Unit) {
    val me = view.viewer
    val enemy = view.opponent.side
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BaseArea(view, enemy, selectedTokenId, onSelect)
            for (band in 5 downTo 1) {
                val pos = me.bandToUsFrame(band)
                BandRow(
                    label = "${band}帯",
                    sub = "${Ja.side(enemy)}から${6 - band}帯",
                    tokens = view.tokens.filter { it.zone == Zone.Band(pos) },
                    viewer = me,
                    selectedTokenId = selectedTokenId,
                    onSelect = onSelect,
                )
            }
            BaseArea(view, me, selectedTokenId, onSelect)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandRow(
    label: String,
    sub: String,
    tokens: List<TokenView>,
    viewer: Side,
    selectedTokenId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF14394A), RoundedCornerShape(6.dp)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            Text(label, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 10.sp, color = Color(0xFFB0BEC5))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (t in tokens) TokenChip(t, viewer, t.id == selectedTokenId, onSelect)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BaseArea(view: PlayerView, side: Side, selectedTokenId: String?, onSelect: (String?) -> Unit) {
    val own = side == view.viewer
    Column(
        Modifier.fillMaxWidth().background(sideColor(side).copy(alpha = 0.25f), RoundedCornerShape(6.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val blocks = if (own) view.me.airbaseBlocksHit else view.opponent.airbaseBlocksHit
        Text(
            "${Ja.side(side)}の基地・CL・スタンドオフ(基地施設の損害 $blocks/3)",
            fontWeight = FontWeight.Bold,
        )
        for (sq in view.squadrons.filter { it.owner == side && it.drafted }) {
            val grounded = view.tokens.count { it.squadronId == sq.id && it.zone == Zone.Grounded && !it.unavailable }
            val unavailable = view.tokens.count { it.squadronId == sq.id && it.unavailable }
            val state = when {
                sq.destroyed -> "撃破済み"
                else -> "損害 ${sq.damage}/2"
            }
            val ground = if (own || sq.faceUp) "  地上 ${grounded}機" + (if (unavailable > 0) "・発進不能 ${unavailable}機" else "") else ""
            Text(
                "[${if (sq.base == BaseKind.AIRBASE) "基地" else "CL"}] ${squadronName(view, sq.id)}" +
                    "${if (sq.faceUp) "" else "(伏せ)"}  $state$ground",
                fontSize = 13.sp,
            )
        }
        val standoff = view.tokens.filter { it.zone == Zone.Standoff(side) || (it.owner == side && it.zone == Zone.AtBase) }
        if (standoff.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in standoff) TokenChip(t, view.viewer, t.id == selectedTokenId, onSelect)
            }
        }
    }
}

@Composable
private fun TokenChip(t: TokenView, viewer: Side, selected: Boolean, onSelect: (String?) -> Unit) {
    val color = sideColor(t.owner)
    val clickable = t.owner == viewer
    Column(
        Modifier
            .widthIn(min = 64.dp)
            .background(if (selected) color else color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Color.White else color), RoundedCornerShape(6.dp))
            .clickable(enabled = clickable) { onSelect(if (selected) null else t.id) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tokenShortName(t), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
        val status = buildList {
            if (t.masked) add("探知${t.acqValue}+")
            if (t.winchester) add("弾切れ")
            if (!t.masked && t.owner == viewer && t.acquired) add("被捕捉")
            if (!t.masked && t.owner != viewer) add("捕捉済")
        }
        if (status.isNotEmpty()) Text(status.joinToString("・"), fontSize = 10.sp, color = Color.White)
    }
}

private fun tokenIdOf(a: Action): String? = when (a) {
    is Action.Move -> a.tokenId
    is Action.Acquire -> a.tokenId
    is Action.ShootToken -> a.tokenId
    is Action.StrikeSquadron -> a.tokenId
    is Action.StrikeAirbaseBlock -> a.tokenId
    else -> null
}

/** 手番中の操作。起動ボタン、選んだ駒の行動、手番の終了を並べる。 */
@Composable
fun ActionPanel(view: PlayerView, engine: afwi.engine.Engine, selectedTokenId: String?, onAct: (Action) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val turn = view.turn
            if (turn == null || view.activeSide != view.viewer) {
                Text("相手の手番です")
            } else {
                fun mark(done: Boolean) = if (done) "済" else "未"
                Text(
                    "この手番: 起動 ${mark(turn.activated)} / 移動 ${mark(turn.moved)} / 捕捉 ${mark(turn.acquired)} / 射撃 ${mark(turn.shot)}",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "飛行隊の起動と、移動・捕捉・射撃(各1回、別々の駒でもよい)は、どちらか一方だけ行えます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                val acts = view.legalActions
                for (a in acts.filterIsInstance<Action.Activate>()) {
                    Button(onClick = { onAct(a) }, modifier = Modifier.fillMaxWidth()) { Text(describe(a, view, engine)) }
                }
                if (selectedTokenId != null) {
                    Text("選択中: ${tokenName(view, selectedTokenId)}", fontWeight = FontWeight.Bold)
                    val tokenActs = acts.filter { tokenIdOf(it) == selectedTokenId }
                    if (tokenActs.isEmpty()) Text("この駒がいまできる行動はありません")
                    for (a in tokenActs) {
                        OutlinedButton(onClick = { onAct(a) }, modifier = Modifier.fillMaxWidth()) { Text(describe(a, view, engine)) }
                    }
                } else if (acts.any { tokenIdOf(it) != null }) {
                    Text("自分の駒をタップすると、その駒の行動を選べます")
                }
                val end = acts.firstOrNull { it is Action.EndTurn || it is Action.Pass }
                if (end != null) {
                    Button(
                        onClick = { onAct(end) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    ) { Text(describe(end, view, engine)) }
                }
            }
        }
    }
}
