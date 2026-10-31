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
                if (ui.screen == Screen.SETTINGS) {
                    SettingsScreen(ui.settings, vm::updateSettings, vm::backToHome)
                } else if (ui.screen == Screen.RULES) {
                    RulesScreen(vm::backToHome)
                } else if (ui.screen == Screen.REPLAY) {
                    ReplayScreen(vm.engine, ui, vm::replayStep, vm::replayViewer, vm::backToHome)
                } else if (game == null || ui.screen == Screen.HOME) {
                    HomeScreen(
                        engine = vm.engine,
                        hasSave = ui.hasSave,
                        hasLastGame = ui.hasLastGame,
                        error = ui.error,
                        onResume = vm::resume,
                        onReplay = vm::openReplay,
                        onRules = vm::openRules,
                        onSettings = vm::openSettings,
                        onHotseat = { campaign -> vm.newHotseatGame(campaign) },
                        onCpu = { campaign, side, difficulty -> vm.newCpuGame(campaign, side, difficulty) },
                    )
                } else {
                    when (ui.screen) {
                        Screen.HANDOFF -> HandoffScreen(ui.holder, vm::confirmHandoff)
                        Screen.RESULT -> ResultScreen(vm.engine.viewFor(game, ui.holder), vm::openReplay, vm::backToHome)
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
    hasSave: Boolean,
    hasLastGame: Boolean,
    error: String?,
    onResume: () -> Unit,
    onReplay: () -> Unit,
    onRules: () -> Unit,
    onSettings: () -> Unit,
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
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (hasSave) {
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("続きから遊ぶ(自動保存した対局)") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("キャンペーン", fontWeight = FontWeight.Bold)
                for (row in engine.content.campaigns.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in row) {
                            ToggleButton(selected = c.id == campaignId, label = c.nameJa, onClick = { campaignId = c.id })
                        }
                    }
                }
                Text("${campaign.nameJa}(ATO ${campaign.atoCount}回)", fontWeight = FontWeight.Bold)
                Text(campaign.descriptionJa)
                Text("ミッション: ${campaign.mission?.let { Ja.mission(it) } ?: "各自が秘密裏に選ぶ"}")
                Text("支援カード: ${if (campaign.enablers) "使う" else "使わない"}")
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
        OutlinedButton(onClick = onRules, modifier = Modifier.fillMaxWidth()) { Text("ルール解説") }
        if (hasLastGame) {
            OutlinedButton(onClick = onReplay, modifier = Modifier.fillMaxWidth()) { Text("前回の対局をリプレイ") }
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("ルール設定") }
        Text(
            "新しく対局を始めると、自動保存した対局は上書きされます。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ルール設定", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "公式ルールで確認できなかった部分を選べます。変更は次に始める対局から反映されます(続きから遊ぶ対局は、保存したときの設定のまま)。",
            style = MaterialTheme.typography.bodySmall,
        )
        SettingRow("艦艇の耐久(何命中で沈むか)", "公式は未確認。ファン実装は3。") {
            for (v in listOf(1, 3)) ToggleButton(settings.shipHp == v, "${v}命中") { onChange(settings.copy(shipHp = v)) }
        }
        SettingRow("基地施設の損害ブロック1つの勝利点", "公式は未確認。") {
            for (v in listOf(0, 1, 2)) ToggleButton(settings.airbaseBlockVp == v, "${v}点") { onChange(settings.copy(airbaseBlockVp = v)) }
        }
        SettingRow("弾切れで帰投した機体の再発進", "同じ ATO 中に補給して再発進できるか(解釈)。") {
            ToggleButton(settings.rtbRearm, "できる") { onChange(settings.copy(rtbRearm = true)) }
            ToggleButton(!settings.rtbRearm, "できない") { onChange(settings.copy(rtbRearm = false)) }
        }
        SettingRow("CPU の速さ", "CPU が1手ごとに待つ時間。") {
            for ((label, ms) in listOf("速い" to 200L, "普通" to 600L, "ゆっくり" to 1200L)) {
                ToggleButton(settings.cpuDelayMs == ms, label) { onChange(settings.copy(cpuDelayMs = ms)) }
            }
        }
        OutlinedButton(onClick = { onChange(AppSettings()) }, modifier = Modifier.fillMaxWidth()) { Text("初期値に戻す") }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("ホームへ戻る") }
    }
}

@Composable
private fun SettingRow(title: String, note: String, options: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(note, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { options() }
        }
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
        view.pending?.let { PendingSection(view, engine, it, onAct) }
        if (view.pending == null) when (view.phase) {
            Phase.MISSION -> ChoiceSection(
                title = "秘密のミッションを選ぶ",
                note = "ミッションで勝利点(VP)の得方が決まります。相手にはゲーム終了まで見えません。",
                view = view, engine = engine, onAct = onAct,
            )
            Phase.ENABLERS -> EnablerSection(view, engine, onAct)
            Phase.POSTURE -> ChoiceSection(
                title = "この ATO の態勢を選ぶ",
                note = "態勢で、参加させられる飛行隊と支援カードの枚数が決まります。標準以外は一度使うと選べません。",
                view = view, engine = engine, onAct = onAct,
            )
            Phase.DRAFT -> DraftSection(view, engine, onAct)
            Phase.DEPLOY -> DeploySection(view, onAct)
            Phase.BID -> BidSection(view, engine, onAct)
            Phase.CHOOSE_ORDER -> ChoiceSection(
                title = "主導権を得ました。先攻を決めてください",
                note = null, view = view, engine = engine, onAct = onAct,
            )
            Phase.TURNS -> {
                Board(view, selectedTokenId, onSelect)
                ActionPanel(view, engine, selectedTokenId, onAct)
                HandPanel(view, engine, onAct)
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
            Text("支援カードの手札: 自 ${me.hand.size}枚 / 敵 ${op.handSize}枚")
        }
    }
}

private fun intelName(level: IntelLevel) = if (level == IntelLevel.ADVANTAGE) "優位" else "通常"

private fun phaseName(view: PlayerView): String = when (view.phase) {
    Phase.MISSION -> "ミッションの選択"
    Phase.ENABLERS -> "支援カードの選択"
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
private fun PendingSection(view: PlayerView, engine: Engine, pending: afwi.engine.Pending, onAct: (Action) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.background(MaterialTheme.colorScheme.tertiaryContainer).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("割り込み: ${pending.promptJa}", fontWeight = FontWeight.Bold)
            for (a in view.legalActions) {
                val r = a as? Action.Respond ?: continue
                r.cardId?.let { Text(engine.content.enabler(it).descriptionJa, style = MaterialTheme.typography.bodySmall) }
                if (r.cardId == null && r.shipId == null) {
                    OutlinedButton(onClick = { onAct(a) }, modifier = Modifier.fillMaxWidth()) { Text(describe(a, view, engine)) }
                } else {
                    Button(onClick = { onAct(a) }, modifier = Modifier.fillMaxWidth()) { Text(describe(a, view, engine)) }
                }
            }
        }
    }
}

@Composable
private fun EnablerSection(view: PlayerView, engine: Engine, onAct: (Action) -> Unit) {
    var selected by remember(view.ato) { mutableStateOf(setOf<String>()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("この ATO の支援カードを選ぶ(上限${view.enablerLimit}枚)", fontWeight = FontWeight.Bold)
            if (view.legalActions.isEmpty()) {
                Text("相手の選択を待っています")
            } else {
                Text("単発カードは一度使うとキャンペーン中は選べません。何度でも使えるカードは ATO ごとに1回使えます。", style = MaterialTheme.typography.bodySmall)
                for (c in view.enablerPool) {
                    val on = c.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (on) selected - c.id else selected + c.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { selected = if (on) selected - c.id else selected + c.id })
                        Column {
                            Text(
                                "${c.nameJa}(${if (c.use == afwi.engine.UseType.SINGLE) "単発" else "毎ATO"}${if (c.action == null) "・応答" else ""})",
                                fontWeight = FontWeight.Bold,
                            )
                            Text(c.descriptionJa, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val legal = selected.size <= view.enablerLimit
                Button(
                    onClick = { onAct(Action.ChooseEnablers(view.viewer, selected)) },
                    enabled = legal, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (legal) "この${selected.size}枚で決定" else "上限を超えています") }
            }
        }
    }
}

@Composable
private fun BidSection(view: PlayerView, engine: Engine, onAct: (Action) -> Unit) {
    var selected by remember(view.ato) { mutableStateOf(setOf<String>()) }
    val bids = view.legalActions.filterIsInstance<Action.SubmitBid>()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("主導権の入札", fontWeight = FontWeight.Bold)
            Text(
                "D4 を振り、差し出した支援カードの枚数を足して大きい方が主導権を得ます。差し出したカードはこの ATO では使えません(使用済みにはなりません)。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (bids.isEmpty()) {
                Text("相手の入札を待っています")
            } else {
                for (id in view.me.hand) {
                    val on = id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (on) selected - id else selected + id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { selected = if (on) selected - id else selected + id })
                        Text(engine.content.enabler(id).nameJa)
                    }
                }
                val action = Action.SubmitBid(view.viewer, selected)
                Button(onClick = { onAct(action) }, enabled = action in bids, modifier = Modifier.fillMaxWidth()) {
                    Text("${selected.size}枚を差し出して入札する")
                }
            }
        }
    }
}

@Composable
private fun HandPanel(view: PlayerView, engine: Engine, onAct: (Action) -> Unit) {
    val me = view.me
    if (me.hand.isEmpty() && me.knownEnemyCards.isEmpty() && me.modifiers.isEmpty() && view.opponent.modifiers.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("支援カード(手番中に1枚使える。応答カードは相手の行動の直後に問い合わせが出る)", fontWeight = FontWeight.Bold)
            val plays = view.legalActions.filterIsInstance<Action.PlayEnabler>()
            for (id in me.hand) {
                val c = engine.content.enabler(id)
                Text("${c.nameJa}${if (c.action == null) "(応答)" else ""}", fontWeight = FontWeight.Bold)
                Text(c.descriptionJa, style = MaterialTheme.typography.bodySmall)
                for (a in plays.filter { it.cardId == id }) {
                    OutlinedButton(onClick = { onAct(a) }, modifier = Modifier.fillMaxWidth()) { Text(describe(a, view, engine)) }
                }
            }
            if (me.modifiers.isNotEmpty()) Text("自分の効果: ${me.modifiers.joinToString("、") { modName(it) }}", style = MaterialTheme.typography.bodySmall)
            if (view.opponent.modifiers.isNotEmpty()) Text("相手の効果: ${view.opponent.modifiers.joinToString("、") { modName(it) }}", style = MaterialTheme.typography.bodySmall)
            if (me.knownEnemyCards.isNotEmpty()) {
                Text("情報判定で見た相手の手札: ${me.knownEnemyCards.joinToString("、") { engine.content.enabler(it).nameJa }}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun modName(m: afwi.engine.ModKind): String = when (m) {
    afwi.engine.ModKind.OWN_AIR_TO_AIR_ADV -> "空対空が有利"
    afwi.engine.ModKind.ENEMY_ACQUIRE_DIS -> "相手の捕捉が不利"
    afwi.engine.ModKind.ENEMY_ATTACK_DIS -> "相手の攻撃が不利"
    afwi.engine.ModKind.OWN_ATTACK_ADV -> "攻撃が有利"
    afwi.engine.ModKind.ENEMY_BASE_ATTACK_DIS -> "相手の基地攻撃が不利"
    afwi.engine.ModKind.H6K_ADV -> "H-6Kが有利"
    afwi.engine.ModKind.NEXT_AIR_TO_AIR_ADV -> "次の空対空が有利"
    afwi.engine.ModKind.CL_MAX_GENERATION -> "CLから全機発進"
    afwi.engine.ModKind.SOF_IMMUNE -> "特殊作戦を受けない"
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
private fun ResultScreen(view: PlayerView, onReplay: () -> Unit, onHome: () -> Unit) {
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
        Button(onClick = onReplay, modifier = Modifier.fillMaxWidth()) { Text("リプレイを見る(隠し情報をすべて開いて1手ずつ)") }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("ホームへ戻る") }
        LogSection(view.log)
    }
}
