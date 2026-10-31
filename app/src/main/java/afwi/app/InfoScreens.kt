package afwi.app

import afwi.engine.Engine
import afwi.engine.Ja
import afwi.engine.Side
import afwi.engine.viewFor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** ルール解説（このアプリが実装しているルールの要約。詳しくは docs/afwi-rules-spec.md）。 */
private val RULE_SECTIONS: List<Pair<String, String>> = listOf(
    "勝ち方" to "キャンペーンの最後に、秘密のミッションで集めた勝利点(VP)が多い方が勝ち。サイバー値が4に達した側は、その場で勝ち。",
    "盤面" to "両軍の基地の間に5本の帯がある。自軍の基地エリアから数えて1〜5帯、相手の基地エリアは6つ目にあたる。米には分散拠点(CL)もある。爆撃機・早期警戒機・電子戦機は自軍のスタンドオフにも置ける。",
    "ATO の流れ" to "1. 態勢を選ぶ(標準以外は一度使うと選べない)\n2. 参加させる飛行隊を選び、基地か CL に伏せて置く\n3. 支援カードを選ぶ(態勢の上限まで)\n4. 主導権の入札: D4 + 差し出したカードの枚数。勝者は先攻・後攻を選び、サイバー判定を1回行い、情報優位を得る\n5. 情報判定: D4 の出目の枚数だけ相手の支援カードが見える(情報優位なら有利)\n6. 手番を交互に行い、両者が続けてパスしたら ATO 終了",
    "手番でできること" to "「飛行隊の起動」か「移動・捕捉・射撃(各1回、別々の駒でもよい)」のどちらか一方と、支援カード1枚。何もしなければパス。",
    "判定" to "D4 で必要な出目以上なら成功。有利は2個振って高い方、不利は低い方。有利と不利は打ち消し合い、重ならない。",
    "捕捉と射撃" to "相手の駒は捕捉するまで正体が分からず、撃てない(基地と CL にいるものは捕捉なしで攻撃できる)。探知値は第4世代機1、ステルス機2、早期警戒機・爆撃機3など。駒は1命中、飛行隊カードは2命中で撃破。爆撃機などの爆発ダイスは、命中すると D4 の損害を与える。",
    "弾切れ" to "航空機は射撃の出目が4未満だと弾切れになり、次の自分の手番の開始時に帰投する。防空は弾切れなし、攻撃型無人機は出目1・2のときだけ、艦艇は弾数ブロックを使い切ったとき。",
    "防空とミサイル防衛" to "基地に防空がいると、その基地への攻撃は命中も損害も不利。艦艇は攻撃の通り道が交戦域にかかるとき、弾数ブロックを1つ使ってミサイル防衛を宣言でき、攻撃側の判定が不利になる。",
    "サイバー" to "初期値1、最大4。上げるには D4 で「今の値+1」以上を出す。4 に達した側の勝ち。",
    "支援カード" to "単発カードはキャンペーン中1回、毎ATOカードは ATO ごとに1回。応答カード(打ち消し・救出など)は、相手の行動の直後に問い合わせが出る。",
    "ミッション" to Ja.missionRule(afwi.engine.MissionId.ATTRITION).let { "消耗: $it\n" } +
        "阻止: ${Ja.missionRule(afwi.engine.MissionId.INTERDICTION)}\n" +
        "兵力節約: ${Ja.missionRule(afwi.engine.MissionId.ECONOMY_OF_FORCE)}\n" +
        "法の執行(米): ${Ja.missionRule(afwi.engine.MissionId.ENFORCE_RULE_OF_LAW)}\n" +
        "非物理的優勢(米): ${Ja.missionRule(afwi.engine.MissionId.NON_KINETIC_DOMINANCE)}\n" +
        "介入阻止(中): ${Ja.missionRule(afwi.engine.MissionId.COUNTER_INTERVENTION)}\n" +
        "三つの優勢(中): ${Ja.missionRule(afwi.engine.MissionId.THREE_DOMINANCES)}",
    "このアプリで仮に決めたこと" to "公式ルールで確認できなかった部分は、次のように扱う(一部はルール設定で変えられる)。\n" +
        "・弾切れで帰投した機体は、同じ ATO 中に再発進できる\n" +
        "・爆発ダイスの損害は、狙った飛行隊 → 同じ基地の他の飛行隊 → 基地施設の順に自動で割り振る\n" +
        "・「出目の数だけ捕捉」などカードの対象はランダムに選ぶ\n" +
        "・艦艇の耐久は1命中、基地施設の損害ブロックは1つ1点\n" +
        "・中国のスタンドオフ態勢は H-6K を上限外で選べる",
)

@Composable
fun RulesScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ルール解説", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Air Force Wargame: Indo-Pacific の Player Guide V1.6 の写しと、運用者が決めたルールに基づく。",
            style = MaterialTheme.typography.bodySmall,
        )
        for ((title, body) in RULE_SECTIONS) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("ホームへ戻る") }
    }
}

/** リプレイ。隠し情報をすべて開いた盤面を、1手ずつ進めたり戻したりして見る。 */
@Composable
fun ReplayScreen(
    engine: Engine,
    ui: UiState,
    onStep: (Int) -> Unit,
    onViewer: (Side) -> Unit,
    onBack: () -> Unit,
) {
    val states = ui.replayStates
    if (states.isEmpty()) {
        Column(Modifier.padding(24.dp)) {
            Text("リプレイできる記録がありません")
            Button(onClick = onBack) { Text("ホームへ戻る") }
        }
        return
    }
    val i = ui.replayIndex
    val view = engine.viewFor(states[i], ui.replayViewer, revealAll = true)
    val prevLog = if (i > 0) engine.viewFor(states[i - 1], ui.replayViewer, revealAll = true).log.size else 0
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("リプレイ ${i}/${states.lastIndex} 手目", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Slider(
            value = i.toFloat(),
            onValueChange = { onStep(it.roundToInt()) },
            valueRange = 0f..states.lastIndex.coerceAtLeast(1).toFloat(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onStep(0) }) { Text("最初") }
            OutlinedButton(onClick = { onStep(i - 1) }) { Text("◀ 1手") }
            OutlinedButton(onClick = { onStep(i + 1) }) { Text("1手 ▶") }
            OutlinedButton(onClick = { onStep(states.lastIndex) }) { Text("最後") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (side in Side.entries) {
                if (side == ui.replayViewer) {
                    Button(onClick = { onViewer(side) }) { Text("✓ ${Ja.side(side)}の視点") }
                } else {
                    OutlinedButton(onClick = { onViewer(side) }) { Text("${Ja.side(side)}の視点") }
                }
            }
        }
        if (i > 0) {
            val action = ui.replayActions[i - 1]
            val before = engine.viewFor(states[i - 1], action.side, revealAll = true)
            Text("この手(${Ja.side(action.side)}): ${describe(action, before, engine)}", fontWeight = FontWeight.Bold)
        }
        Text(
            "ATO ${view.ato}/${view.atoCount}  VP 米 ${if (view.viewer == Side.US) view.me.vp else view.opponent.vp ?: 0}" +
                " / 中 ${if (view.viewer == Side.PRC) view.me.vp else view.opponent.vp ?: 0}" +
                "  サイバー ${Ja.side(view.viewer)} ${view.me.cyber} / ${Ja.side(view.opponent.side)} ${view.opponent.cyber}",
        )
        Board(view, null) { }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("この手で起きたこと", fontWeight = FontWeight.Bold)
                HorizontalDivider()
                val lines = view.log.drop(prevLog)
                if (lines.isEmpty()) Text("(この視点に見える出来事はなし)", style = MaterialTheme.typography.bodySmall)
                for (line in lines) Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("ホームへ戻る") }
    }
}
