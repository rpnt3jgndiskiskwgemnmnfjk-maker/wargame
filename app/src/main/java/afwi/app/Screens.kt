package afwi.app

import afwi.ai.Difficulty
import afwi.engine.Action
import afwi.engine.BaseKind
import afwi.engine.Zone
import afwi.engine.Engine
import afwi.engine.IntelLevel
import afwi.engine.Ja
import afwi.engine.Phase
import afwi.engine.PlayerView
import afwi.engine.Side
import afwi.engine.viewFor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AfwiApp(vm: GameViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val game = ui.game
                if (game == null || ui.screen == Screen.HOME) {
                    HomeScreen(
                        engine = vm.engine,
                        onHotseat = { campaign -> vm.newHotseatGame(campaign) },
                        onCpu = { campaign, side, difficulty -> vm.newCpuGame(campaign, side, difficulty) },
                    )
                } else {
                    when (ui.screen) {
                        Screen.HANDOFF -> HandoffScreen(ui.holder, vm::confirmHandoff)
                        Screen.RESULT -> ResultScreen(vm.engine.viewFor(game, ui.holder), vm::backToHome)
                        else -> PlayScreen(
                            view = vm.engine.viewFor(game, ui.holder),
                            engine = vm.engine,
                            modeLabel = modeLabel(ui.mode),
                            cpuThinking = ui.cpuThinking,
                            selectedTokenId = ui.selectedTokenId,
                            error = ui.error,
                            onSelect = vm::select,
                            onAct = vm::act,
                        )
                    }
                }
            }
        }
    }
}

private fun modeLabel(mode: Mode): String = when (mode) {
    is Mode.Hotseat -> "ホットシート"
    is Mode.VsCpu -> "対CPU(${mode.difficulty.labelJa})"
}

@Composable
private fun HomeScreen(
    engine: Engine,
    onHotseat: (String) -> Unit,
    onCpu: (String, Side, Difficulty) -> Unit,
) {
    var campaignId by remember { mutableStateOf("C1") }
    val campaign = engine.content.campaign(campaignId)
    var humanSide by remember { mutableStateOf(Side.US) }
    var difficulty by remember { mutableStateOf(Difficulty.NORMAL) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("AFWI", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("Air Force Wargame: Indo-Pacific(非公式アプリ)", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("キャンペーン", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in engine.content.campaigns) {
                        ToggleButton(selected = c.id == campaignId, label = c.nameJa, onClick = { campaignId = c.id })
                    }
                }
                Text("${campaign.nameJa}(ATO ${campaign.atoCount}回)", fontWeight = FontWeight.Bold)
                Text(campaign.descriptionJa)
                Text("ミッション: ${Ja.mission(campaign.mission)}")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CPU と対戦", fontWeight = FontWeight.Bold)
                Text("自分の陣営")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (side in Side.entries) {
                        ToggleButton(selected = humanSide == side, label = "${Ja.side(side)}軍", onClick = { humanSide = side })
                    }
                }
                Text("CPU の強さ")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in Difficulty.entries) {
                        ToggleButton(selected = difficulty == d, label = d.labelJa, onClick = { difficulty = d })
                    }
                }
                Button(onClick = { onCpu(campaignId, humanSide, difficulty) }, modifier = Modifier.fillMaxWidth()) {
                    Text("CPU と対戦を始める")
                }
            }
        }
        OutlinedButton(onClick = { onHotseat(campaignId) }, modifier = Modifier.fillMaxWidth()) {
            Text("ホットシートで対局を始める(1台を交代で使う)")
        }
        Text(
            "現在の版: 遭遇戦と大会を遊べます。支援カードと残りのキャンペーンは今後の版で追加します。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ToggleButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text("✓ $label") }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun HandoffScreen(holder: Side, onConfirm: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(sideColor(holder)).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${Ja.side(holder)}側に端末を渡してください",
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = sideColor(holder)),
        ) {
            Text("${Ja.side(holder)}側が受け取った")
        }
    }
}

@Composable
private fun PlayScreen(
    view: PlayerView,
    engine: Engine,
    modeLabel: String,
    cpuThinking: Boolean,
    selectedTokenId: String?,
    error: String?,
    onSelect: (String?) -> Unit,
    onAct: (Action) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusHeader(view, engine, modeLabel)
        if (cpuThinking) {
            Text("CPU が考えています…", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
        }
        if (error != null) Text("操作できません: $error", color = MaterialTheme.colorScheme.error)
        when (view.phase) {
            Phase.POSTURE -> ChoiceSection(
                title = "この ATO の態勢を選ぶ",
                note = "態勢で、参加させられる飛行隊と支援カードの枚数が決まります。標準以外は一度使うと選べません。",
                view = view, engine = engine, onAct = onAct,
            )
            Phase.DRAFT -> DraftSection(view, engine, onAct)
            Phase.DEPLOY -> DeploySection(view, onAct)
            Phase.BID -> ChoiceSection(
                title = "主導権の入札",
                note = "D4 を振り、差し出した支援カードの枚数を足して大きい方が主導権を得ます(支援カードは今後の版で追加)。",
                view = view, engine = engine, onAct = onAct,
            )
            Phase.CHOOSE_ORDER -> ChoiceSection(
                title = "主導権を得ました。先攻を決めてください",
                note = null, view = view, engine = engine, onAct = onAct,
            )
            Phase.TURNS -> {
                Board(view, selectedTokenId, onSelect)
                ActionPanel(view, engine, selectedTokenId, onAct)
            }
            Phase.GAME_OVER -> Unit
        }
        LogSection(view.log)
    }
}

@Composable
private fun StatusHeader(view: PlayerView, engine: Engine, modeLabel: String) {
    val me = view.me
    val op = view.opponent
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.background(sideColor(view.viewer).copy(alpha = 0.35f)).padding(12.dp)) {
            Text(
                "$modeLabel / ${Ja.side(view.viewer)}側の画面 / ATO ${view.ato}/${view.atoCount} / ${phaseName(view)}",
                fontWeight = FontWeight.Bold,
            )
            Text("ミッション: ${Ja.mission(me.mission)}(相手には非公開)")
            Text("態勢: 自 ${me.posture?.let { engine.content.posture(it).nameJa } ?: "未選択"} / 敵 ${op.posture?.let { engine.content.posture(it).nameJa } ?: "非公開"}")
            Text(
                "サイバー値 ${Ja.side(view.viewer)} ${me.cyber} / ${Ja.side(op.side)} ${op.cyber}(4で即勝利)" +
                    "  情報: ${intelName(me.intel)}",
            )
            Text("獲得VP(自分): ${me.vp}  基地施設の損害: 自 ${me.airbaseBlocksHit} / 敵 ${op.airbaseBlocksHit}")
        }
    }
}

private fun intelName(level: IntelLevel) = if (level == IntelLevel.ADVANTAGE) "優位" else "通常"

private fun phaseName(view: PlayerView): String = when (view.phase) {
    Phase.POSTURE -> "態勢の選択"
    Phase.DRAFT -> "飛行隊の選択"
    Phase.DEPLOY -> "配置"
    Phase.BID -> "主導権の入札"
    Phase.CHOOSE_ORDER -> "先攻の決定"
    Phase.TURNS -> if (view.activeSide == view.viewer) "自分の手番" else "相手の手番"
    Phase.GAME_OVER -> "終了"
}

@Composable
private fun ChoiceSection(title: String, note: String?, view: PlayerView, engine: Engine, onAct: (Action) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
            for (sq in view.squadrons.filter { it.owner == view.viewer && it.drafted }) {
                Text("自分の飛行隊: ${squadronName(view, sq.id)}(${sq.def?.count ?: 0}機)")
            }
            if (view.legalActions.isEmpty()) Text("相手の行動を待っています")
            for (a in view.legalActions) {
                Button(onClick = { onAct(a) }, modifier = Modifier.fillMaxWidth()) { Text(describe(a, view, engine)) }
            }
        }
    }
}

/** 機種の能力を1行で。 */
private fun typeStats(type: afwi.engine.TokenType): String = buildString {
    append("移動${type.move} 探知射程${type.acqRange}")
    type.air?.let { append(" 対空${it.range}帯/${it.hit}+") }
    type.surface?.let { append(" 対地${it.range}帯/${it.hit}+${if (it.explosive) "(爆発)" else ""}") }
    append(" 探知値${type.acqValue}")
}

@Composable
private fun DraftSection(view: PlayerView, engine: Engine, onAct: (Action) -> Unit) {
    var selected by remember(view.ato) { mutableStateOf(setOf<String>()) }
    val drafts = view.legalActions.filterIsInstance<Action.Draft>()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("この ATO に参加させる飛行隊を選ぶ", fontWeight = FontWeight.Bold)
            if (drafts.isEmpty()) {
                Text("相手の選択を待っています")
            } else {
                val posture = view.me.posture?.let { engine.content.posture(it) }
                val candidates = drafts.flatMap { it.squadronIds }.toSet()
                val freeNote = posture?.freeSquadrons?.takeIf { it.isNotEmpty() }
                    ?.joinToString("、") { engine.content.squadron(it).nameJa }?.let { "。上限外: $it" } ?: ""
                Text("態勢「${posture?.nameJa}」: 上限${posture?.squadronLimit}枚$freeNote", style = MaterialTheme.typography.bodySmall)
                for (sq in view.squadrons.filter { it.owner == view.viewer && it.id in candidates }) {
                    val def = sq.def ?: continue
                    val type = engine.content.tokenType(def.tokenType)
                    val live = view.tokens.count { it.squadronId == sq.id && it.zone != Zone.Destroyed }
                    val on = sq.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (on) selected - sq.id else selected + sq.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { selected = if (on) selected - sq.id else selected + sq.id })
                        Column {
                            Text("${def.nameJa}(${type.nameJa}) 残り${live}/${def.count}機", fontWeight = FontWeight.Bold)
                            Text(typeStats(type), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val action = Action.Draft(view.viewer, selected)
                val legal = action in drafts
                Button(onClick = { onAct(action) }, enabled = legal, modifier = Modifier.fillMaxWidth()) {
                    Text(if (legal) "この${selected.size}枚で決定" else "選べる枚数を超えています")
                }
            }
        }
    }
}

@Composable
private fun DeploySection(view: PlayerView, onAct: (Action) -> Unit) {
    var placement by remember(view.ato) { mutableStateOf(mapOf<String, BaseKind>()) }
    val deploys = view.legalActions.filterIsInstance<Action.Deploy>()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("飛行隊カードを伏せて配置する", fontWeight = FontWeight.Bold)
            if (deploys.isEmpty()) {
                Text("相手の配置を待っています")
            } else {
                val ids = deploys.first().placements.keys.toList()
                val clAllowed = deploys.any { BaseKind.CL in it.placements.values }
                if (clAllowed) {
                    Text(
                        "CL(分散拠点)に置くと基地への攻撃に巻き込まれにくい代わりに、発進時に D4 の出目の数しか発進できません。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for (id in ids) {
                    val base = placement[id] ?: BaseKind.AIRBASE
                    Text(squadronName(view, id), fontWeight = FontWeight.Bold)
                    if (clAllowed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (b in BaseKind.entries) {
                                ToggleButton(selected = base == b, label = Ja.base(b), onClick = { placement = placement + (id to b) })
                            }
                        }
                    }
                }
                val action = Action.Deploy(view.viewer, ids.associateWith { placement[it] ?: BaseKind.AIRBASE })
                Button(onClick = { onAct(action) }, enabled = action in deploys, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ids.isEmpty()) "配置する飛行隊なしで決定" else "この配置で決定")
                }
            }
        }
    }
}

@Composable
private fun LogSection(log: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("履歴(新しい順)", fontWeight = FontWeight.Bold)
            HorizontalDivider()
            for (line in log.asReversed().take(40)) {
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ResultScreen(view: PlayerView, onHome: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val winner = view.winner
        val result = if (winner == null) "引き分け" else "${Ja.side(winner)}の勝ち"
        Text(result, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = winner?.let { sideColor(it) } ?: Color.Unspecified)
        Text(view.gameOverReason ?: "")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("${Ja.side(view.viewer)}: ${view.me.vp} VP(${Ja.mission(view.me.mission)})")
                Text("${Ja.side(view.opponent.side)}: ${view.opponent.vp ?: 0} VP(${view.opponent.mission?.let { Ja.mission(it) } ?: ""})")
            }
        }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("ホームへ戻る") }
        LogSection(view.log)
    }
}
