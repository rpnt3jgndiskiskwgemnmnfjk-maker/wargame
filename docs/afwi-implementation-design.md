# AFWI をプログラムで再現するための設計

- 作成日：2026-10-31
- 前提文書：[afwi-rules-spec.md](./afwi-rules-spec.md)（以下「仕様書」。§番号は仕様書の節を指す）
- 位置づけ：仕様書のルールを、コンピュータ上で再現する方法を設計の観点から整理したもの。実装言語に依存しないよう書き、コード例は TypeScript 風の擬似コードとした。

---

## 1. 再現の難しさ（論点の整理）

| 論点 | なぜ難しいか | 設計上の答え |
|---|---|---|
| 隠匿情報 | ミッション、伏せた飛行隊、手札、未捕捉のトークン、情報判定で見せた手札など、**プレイヤーごとに見えるものが違う** | 正規の状態（真の状態）と、プレイヤーごとの**視点（ビュー）**を分ける。§4 |
| 割り込み | 応答（Response）カード、ミサイル防衛の宣言、攻撃の宣言→防衛→判定、という**多段階の手続き** | 行動を「宣言」と「解決」に分け、**割り込みの窓**を明示した状態機械にする。§5 |
| カード効果の多様さ | 68 種類のイネーブラーがそれぞれ違う効果を持つ | 効果を**小さな命令（オペコード）の組み合わせ**として、データで書く。§6 |
| 有利・不利の重なり | 有利と不利は相殺され、どちらも重ならない | 修正要因を集めてから最後に一度だけ判定する。§7 |
| 確率の再現性 | 振り直し、テスト、リプレイのため | 種（シード）を指定できる乱数を使い、全判定をログに残す。§7 |
| ルールの未確認部分 | 仕様書 §14 の U1〜U9 | **設定値（ルール変種）として外に出す**。§9 |

---

## 2. 全体構成

```
┌──────────────┐   行動（Command）     ┌──────────────────┐
│ UI / AI / Net │ ───────────────────▶ │  Rules Engine     │
│  (表示・入力)  │ ◀─────────────────── │  （純粋関数）       │
└──────────────┘  視点(View)＋イベント  │  state' = f(state, │
                                        │           cmd, rng)│
                                        └────────┬─────────┘
                                                 │ 参照
                                        ┌────────▼─────────┐
                                        │ Content Data      │
                                        │ (カード・トークン・ │
                                        │  態勢・ミッション)  │
                                        └──────────────────┘
```

- **ルールエンジン**は UI に依存しない純粋なロジックにする。入力は「状態、行動、乱数」、出力は「新しい状態とイベントの列」。
- **コンテンツ**（カードの能力値）は JSON などのデータで持ち、コードから切り離す。拡張（Europe）や数値の修正をデータの差し替えで行えるようにする。
- **UI と AI** は同じ「行動」インターフェースを使う。ホットシート対戦、AI 対戦、ネット対戦を同じエンジンで実現できる。

---

## 3. データモデル

### 3.1 位置の表現

仕様書 §3 の座標を採用し、**US 基準の位置**を整数で持つ。

```ts
type Side = 'US' | 'PRC';
type Zone =
  | { kind: 'band'; pos: 1 | 2 | 3 | 4 | 5 }            // US 基準の帯番号
  | { kind: 'airbase' | 'standoff' | 'cl'; side: Side } // CL は US のみ
  | { kind: 'card' }                                     // 飛行隊カード上（地上）
  | { kind: 'offboard' };

function coord(z: Zone): number | null {
  switch (z.kind) {
    case 'band': return z.pos;
    case 'airbase': case 'standoff': case 'cl':
      return z.side === 'US' ? 0 : 6;
    default: return null;               // 地上（カード上）は所属基地の座標で扱う
  }
}
const distance = (a: Zone, b: Zone) => Math.abs(coord(a)! - coord(b)!);

// 表示用：その陣営から見た帯番号
const bandLabel = (pos: number, viewer: Side) => viewer === 'US' ? pos : 6 - pos;
```

### 3.2 静的データ（コンテンツ）

```ts
interface TokenType {
  id: string;                    // 'F-35A' など
  side: Side;
  cls: 'Gen5' | 'Gen4' | 'Bomber' | 'AEW' | 'EW' | 'UAS_ATTACK' | 'UAS_RECON'
     | 'ADA' | 'SHIP';
  move: number;                  // 移動力
  acq?: { range: number; tries: number; bonus: number }; // 捕捉能力
  acqDifficulty: number;         // 裏面の値。仕様書 §11.1（戦闘機1、第5世代2、AEW・爆撃機3 など）
  air?: WeaponProfile;           // 対空（ミサイルのアイコン）
  surface?: WeaponProfile;       // 対地・対水上（爆弾のアイコン）
  missileDefense?: { wez: number };
  winchester: 'standard' | 'infinite' | 'none' | 'salvo';
  salvos?: { air: number; surface: number };             // 艦艇のみ
  hp: number;                    // 通常は1。艦艇は U5（設定値）
  vpClass: 'Ship' | 'Bomber' | 'ADA' | 'AEW' | 'Other';  // スコア計算用
}
interface WeaponProfile { range: number; hit: number; explosive: boolean; }

interface SquadronCard {
  id: string; side: Side; name: string;
  tokenType: string; count: number;
  generate: 'band1' | 'anyBand' | 'band1OrStandoff' | 'airbase';
  isFlying: boolean;             // JOINT OPERATIONS 判定用
  tags: string[];                // 'Gen5', 'Bomber', 'LongRangeADA' など。キャンペーン制限用
}

interface EnablerCard {
  id: string; side: Side; title: string; family: string;
  use: 'single' | 'multiple';
  timing: 'action' | 'response' | 'anytime';
  enduring: boolean;
  tags: string[];                // 'Cyber','Space','EW','SOF','PLARF','Naval','Unblockable' など
  effect: EffectScript;          // §6
}

interface PostureCard {
  id: string; side: Side; title: string;
  sqLimit: number; enLimit: number;
  reusable: boolean;             // STANDARD だけ true
  modifiers: RuleModifier[];     // 'airbaseStrikesAtDisadvantage' など
}

interface MissionCard { id: string; sides: Side[]; scoring: ScoringRule[]; }
```

### 3.3 動的状態（真の状態）

```ts
interface GameState {
  campaign: CampaignDef;
  atoIndex: number;
  phase: Phase;                          // §5 の状態機械
  players: Record<Side, PlayerState>;
  tokens: Record<TokenId, TokenInstance>;
  squadrons: Record<SqId, SquadronInstance>;
  turn: { active: Side; consecutivePasses: number; did: { primary: boolean; enabler: boolean } };
  pending: PendingStack;                 // 割り込みの窓（§5.3）
  effects: ActiveEffect[];               // 持続効果（赤い画鋲）や態勢の修正
  log: GameEvent[];
  rngState: RngState;
}

interface PlayerState {
  mission: MissionId;                    // 非公開
  posture?: PostureId; usedPostures: PostureId[];
  cyber: 0 | 1 | 2 | 3 | 4;
  intel: 'Normal' | 'Advantage';
  enablerHand: EnablerId[];              // 非公開
  revealedToOpponent: EnablerId[];       // 情報判定で相手に見せたカード
  spentSingleUse: EnablerId[];           // キャンペーン中は使用不可
  deadSquadrons: SqId[]; deadTokens: TokenTypeCount;   // 損耗の持ち越し（U9）
  captured: CapturedRecord[];            // 自分が撃破したもの（VP 計算用）
  vp: number;
}

interface TokenInstance {
  id: TokenId; type: string; owner: Side; squadron?: SqId;
  zone: Zone;
  acquired: boolean;                     // 表向きか（全員に公開）
  winchester: boolean; salvoUsed?: { air: number; surface: number };
  damage: number;
  generatedBy: 'squadron' | 'enabler';
}

interface SquadronInstance {
  id: SqId; card: string; owner: Side;
  base: 'airbase' | 'cl';
  faceUp: boolean;                       // 起動済み＝表向き
  damage: number;                        // 2点で撃破
  unavailable: number;                   // CL の判定で生成できなかった機数
}
```

---

## 4. 隠匿情報と視点（ビュー）

**原則**：エンジンは常に真の状態を持つ。UI、AI、ネットワークには、**視点関数で作ったビューだけ**を渡す。これでチートや情報の漏れを構造的に防げる。

```ts
function viewFor(state: GameState, viewer: Side): PlayerView {
  const opp = other(viewer);
  return {
    me: state.players[viewer],                         // 自分の分はすべて見える
    opponent: {
      mission: state.phase === 'GameOver' ? state.players[opp].mission : HIDDEN,
      posture: state.players[opp].posture,             // 態勢は公開
      cyber: state.players[opp].cyber, intel: state.players[opp].intel,
      handSize: state.players[opp].enablerHand.length,
      knownEnablers: state.players[viewer].intelPeek,  // 情報判定で見たカード
    },
    tokens: Object.values(state.tokens).map(t =>
      t.owner === viewer || t.acquired
        ? t                                            // 自分の駒と捕捉済みの駒は詳細が見える
        : { id: t.id, owner: t.owner, zone: t.zone, masked: true, acqDifficulty: typeOf(t).acqDifficulty }),
    squadrons: /* 相手の伏せカードは場所と枚数だけ */,
    log: state.log.filter(e => e.visibleTo.includes(viewer)),
  };
}
```

- 未捕捉の敵トークンについて見えるのは「どこかに敵がいる」ことと、**裏面の値**（捕捉に必要な値）だけ。機種は分からない（仕様書 §7.3）。
- イベントログにも `visibleTo` を付ける。例えば、相手の情報判定でどのカードを見せたかは、見た側にだけ通知する。
- ネット対戦では、**サーバーだけがエンジンを持つ**権威サーバー方式にする。クライアントにはビューだけを送る。

---

## 5. 進行の状態機械

### 5.1 フェーズ

```
CampaignSetup
  └▶ ATO_Posture ─▶ ATO_DraftSquadrons ─▶ ATO_Deploy ─▶ ATO_DraftEnablers
       ─▶ Initiative_Bid ─▶ Initiative_Roll ─(同点なら振り直し)
       ─▶ Initiative_Reward（先攻を選ぶ／サイバー判定／Intel）
       ─▶ Intel_Roll ─▶ Intel_Reveal
       ─▶ Turn_Start ─▶ Turn_Main ⇄ (割り込みの窓) ─▶ Turn_End ─┐
            ▲                                                 │
            └────────────── 相手の手番へ ◀──────────────────────┘
       （両者が続けてパスしたら）─▶ ATO_Cleanup ─▶ ATO_Scoring
  └▶（ATO が残っていれば ATO_Posture へ）─▶ GameOver（ミッションを公開して最終集計）
※ 任意の時点：誰かのサイバーが4になったら即 GameOver
```

- **同時に秘密裏に決める**場面（入札枚数、ミッション、イネーブラーの選択）は、両者が提出するまで待つ「同時入力フェーズ」として扱う。ホットシートでは画面の受け渡しを挟む。

### 5.2 手番の処理

```ts
function legalCommands(s: GameState, side: Side): Command[] {
  const cmds: Command[] = [];
  if (!s.turn.did.primary) {
    cmds.push(...activatableSquadrons(s, side).map(id => ({ t: 'Activate', id })));
    cmds.push(...masOptions(s, side));          // MAS の各段階は小さな行動に分ける
  }
  if (!s.turn.did.enabler) cmds.push(...playableEnablers(s, side, 'action'));
  cmds.push({ t: s.turn.did.primary || s.turn.did.enabler ? 'EndTurn' : 'Pass' });
  return cmds;
}
```

- MAS 行動は `MasMove`、`MasAcquire`、`MasShoot` の3つの小さな行動に分ける。行動ごとに使用済みの印を付け、どれも1回までに制限する。1つでも実行したら、その手番の主行動（primary）は使用済みになり、飛行隊の起動はできなくなる。
- **パスの判定**：手番中に何もしなかった場合だけ `Pass` とし、`consecutivePasses++`。何かしたら0に戻す。2になったら ATO 終了。
- **手番の開始時の処理**：Winchester の航空機を飛行隊カードへ帰投させ、艦艇を盤外へ出す（無料。仕様書 §7.5）。

### 5.3 割り込みの窓（攻撃の解決手順）

攻撃は次の段階に分けて解決し、各段階の間で相手に応答の機会を与える。

```
1. AttackDeclared(攻撃元, 目標, 武器)
     └▶ 窓 W1：防衛側 ── ミサイル防衛を宣言できるか判定
                       　 （WEZ と交差するか、Unblockable でないか、艦艇の斉射ブロックが残っているか）
                       ── 応答カード（例：囮弾頭への対抗）
2. 攻撃側の付随カード（例：Forward Bases で自動命中）
3. HitRoll（有利・不利を集計して判定）
     └▶ 窓 W2：防衛側 ── 命中を打ち消すカード（例：Air Launched Decoy）
4. DamageRoll（爆発ダイスなら D4 を振る）
     └▶ 窓 W3：防衛側 ── 損害を全部打ち消すカード（例：Red Horse、Resilient Bases）
5. 攻撃側が損害を割り振る（Airbase 内で自由に／CL なら飛行隊1つだけ）
6. 撃破の処理 → 撃破物を記録 → 窓 W4：Personnel Recovery などの「失った後」に使う応答
7. Winchester の判定（出目 4 未満なら弾切れ。種類ごとの例外あり）
```

- 実装では `pending` を**スタック**として持つ。応答カードにさらに応答できる（例：相手の打ち消しを打ち消す）場合でも、同じ仕組みで扱える。
- 各窓では、応答できる側が「使う」か「使わない」かを必ず明示的に返す。こうすることで AI でも人間でも同じ流れになる。UI では、使えるカードがなければ自動で「使わない」を返す。

---

## 6. カード効果の書き方

### 6.1 方針

68枚の効果は、少数の基本命令を組み合わせると表現できる。効果を**データ（効果スクリプト）**として書き、エンジンはそれを解釈するだけにする。

| 基本命令 | 引数 | 使うカードの例 |
|---|---|---|
| `roll` | 閾値、有利・不利の設定 | HIMARS（2+） |
| `acquire` | 数（定数、ダイスの出目、サイバー×k） | Space Recon、Cyber Recon |
| `removeTokens` / `destroyTokens` | 数、条件（UAS だけ、など） | Offensive Cyber、Cyber Counter-UAS |
| `damageBase` | 数（定数またはダイス）、Unblockable かどうか | 弾道ミサイル、SOF 突襲 |
| `destroyShip` | 自動命中 | 対艦巡航ミサイル、Submarine Strike |
| `cyberDelta` | ±n（自分／相手） | Joint Defensive Cyber（−2） |
| `cyberAttempt` | — | Cyber Infiltration |
| `generate` | トークンの種類、数、場所 | DDG 81、J-15×4、EC-130 |
| `addModifier` | 対象の判定、有利・不利、期間（次の1回／ATO中） | EW Spoofing、Improved Munitions |
| `cancel` | 打ち消す対象（カードの種類、命中、損害、ミサイル防衛） | Counter Space、Red Horse |
| `recover` | 撃破を取り消して自陣 1 帯へ戻す | Personnel Recovery |
| `forceDiscard` | 数 | Offensive Cyber（もう一方の選択肢） |
| `draftBonus` | 飛行隊の上限 +1 | Aerial Refueling |
| `returnCard` | 使用済みのカードを戻す | Rapid Resupply、衛星網の再建 |
| `choice` | 選択肢の列 | Offensive Cyber、Defensive Cyber |

### 6.2 記述例（JSON）

```json
{ "id": "US-OFFENSIVE-CYBER", "use": "multiple", "timing": "action", "tags": ["Cyber"],
  "effect": { "choice": [
      { "removeTokens": { "count": { "cyberRate": 1 }, "target": "enemy" } },
      { "forceDiscard": { "count": { "cyberRate": 1 } } } ] } }

{ "id": "PRC-HYPERSONIC", "use": "single", "timing": "action", "tags": ["PLARF","Unblockable"],
  "effect": { "damageBase": { "amount": { "d4": true }, "unblockable": true } } }

{ "id": "US-AIR-LAUNCHED-DECOY", "use": "single", "timing": "response",
  "trigger": ["enemyHitSucceeded", "enemyMissileDefenseDeclared"],
  "effect": { "cancel": "triggeringEvent" } }

{ "id": "US-EW-SPOOFING", "use": "single", "timing": "action", "enduring": true, "tags": ["EW"],
  "effect": { "addModifier": { "roll": "acquire", "who": "enemy", "mode": "disadvantage", "duration": "ato" } } }
```

- 応答カードには `trigger`（どのイベントで使えるか）を持たせる。§5.3 の各窓で、条件に合う手札を候補として示す。
- **ミッションの VP 計上**も、同じようにイベントを購読する**ルール（スコアラー）**として書く（例：`onDestroyed(token) → vpClass が Bomber なら +4`）。「キャンペーン中に PLARF カードを使った回数」のような集計も、イベントログから求められる。

---

## 7. ダイスと有利・不利

```ts
type Mode = 'normal' | 'adv' | 'dis';

function resolveMode(mods: Mode[]): Mode {      // 重ならない／相殺する
  const a = mods.includes('adv'), d = mods.includes('dis');
  return a && !d ? 'adv' : d && !a ? 'dis' : 'normal';
}

function rollD4(rng: Rng, mode: Mode, bonus = 0) {
  const r1 = rng.int(1, 4);
  const r2 = mode === 'normal' ? null : rng.int(1, 4);
  const pick = r2 === null ? r1 : mode === 'adv' ? Math.max(r1, r2) : Math.min(r1, r2);
  return { raw: [r1, r2], natural: pick, total: pick + bonus };
}
```

- 有利・不利の**要因を集める関数**を1か所にまとめる。要因には、態勢（ACE なら基地攻撃が不利）、持続効果（EW など）、ミサイル防衛、地上 ADA の WEZ、カード（精鋭パイロットなど）がある。こうすると相殺漏れや二重適用が起きない。
- **Winchester の判定**には、ボーナスを足す前の素の出目（`natural`）を使うのが妥当と考える（「4は全弾を使わずに当てた」という意味のため）[推定]。
- 乱数は**シード付きの擬似乱数**（例：xoshiro128**、PCG）を使う。シードと行動列があれば対局を完全に再現でき、リプレイやバグ報告に役立つ。
- 補足：攻撃結果の期待値などを AI に評価させたいとき、D4 の組み合わせは高々16通りなので、確率は列挙で正確に計算できる（仕様書 §6.2）。

---

## 8. AI（コンピュータ対戦相手）

段階的に作ることを推奨する。

| 段階 | 方式 | 特徴 |
|---|---|---|
| 1 | ランダム（合法手から一様に選ぶ） | テストと耐久確認用 |
| 2 | ルールベース（優先順位表） | 例：「捕捉済みの高 VP 目標を射程内に撃てるなら撃つ」「未捕捉なら AEW で捕捉」「弾切れが近いならパス」。ミッションによって重みを変える |
| 3 | 期待値による1手読み | 各合法手の期待 VP 変化と期待損失を、§7 の正確な確率で評価して最大の手を選ぶ |
| 4 | 決定化モンテカルロ木探索（IS-MCTS） | 隠匿情報（相手の手札・ミッション・伏せ駒）を、**ビューと矛盾しないようにランダムに割り当てて**何度も探索する。不完全情報ゲームに使われる標準的な手法 |

- AI には必ず**ビュー（§4）だけ**を渡す。真の状態を見る AI は作らない。
- ドラフト（態勢、飛行隊、イネーブラー）や入札は、局面の評価関数とミッションとの相性で決める。

---

## 9. 未確認ルールを設定値にする

仕様書 §14 の未確認事項は、コードに直接書き込まず**ルール設定**として外に出す。公式ルールが確認できたら、設定を変えるだけで済む。

```ts
interface RuleVariant {
  cyberThresholds: [number, number, number, number]; // U1：確定値 [1,2,3,4]（0→1, 1→2, 2→3, 3→4。必要な出目＝現在値＋1）
  initiativeWinner: 'choosesOrder' | 'alwaysFirst';  // U7：確定値 choosesOrder（勝者が先攻・後攻を選ぶ）
  shipHp: number;                                    // U5：既定値 1、ファン版互換なら 3
  airbaseDamageBlockVP: number;                      // U4
  persistAttrition: boolean;                         // U9：既定値 true
  wezCrossing: 'intervalOverlap' | 'targetInside';   // U8
  attritionCountsADA: boolean;                       // 仕様書 §9 の両論：S1 に合わせて true
}
```

---

## 10. テストの方針

| 種類 | 内容 |
|---|---|
| 単体テスト | 距離計算、有利・不利の相殺、Winchester の各種類、CL の生成判定、2点で飛行隊が撃破されること、地上で撃破したら所属トークンも全部撃破されること |
| シナリオテスト | 仕様書の図解例を再現する。F-35（US 2 帯）が J-10（US 3 帯）を捕捉 2+ → 射撃 2+。B-52（Standoff）が PRC 基地を射程 6 で攻撃し、Nanning 駆逐艦の WEZ でミサイル防衛 → 不利になる |
| 不変条件テスト | 乱数で何千局も自動対局し、毎手番で確かめる。トークン総数が保存されること。サイバーが 0〜4 に収まること。同じ態勢を2回使わないこと（STANDARD を除く）。ビューに非公開情報が漏れていないこと |
| 統計テスト | 判定を大量に振り、成功率が仕様書 §6.2 の理論値と一致することを確かめる（カイ二乗検定など） |
| リプレイテスト | 同じシードと行動列から、同じ最終状態になること |

---

## 11. 実装の段階計画（推奨）

| 段階 | 範囲 | 完了の目安 |
|---|---|---|
| M1 | キャンペーン1「遭遇戦」（F-16 対 J-10、イネーブラーなし、1 ATO、Attrition） | 起動、MAS、Winchester、ATO 終了、VP 計上まで CLI で遊べる |
| M2 | 全飛行隊、態勢、CL、AEW、爆撃機、ADA、ミサイル防衛 | キャンペーン2「大会」が遊べる |
| M3 | イネーブラー68枚（効果スクリプト）、応答の窓、サイバー、情報判定 | キャンペーン3「長期戦」が遊べる |
| M4 | 全ミッション、キャンペーン4・5、ルール設定 | 全キャンペーンが遊べる |
| M5 | GUI、ホットシートの受け渡し、AI 段階1〜3 | 一人でも遊べる |
| M6 | ネット対戦（権威サーバー）、IS-MCTS、Europe 拡張（データの追加と DIME フェーズ） | — |

- **技術の選び方の例**：Web で配布するなら、エンジンは TypeScript の純粋関数、表示は Canvas か React にする。シミュレーションを大量に回すなら Python か Rust で同じエンジンを実装する方法もある。どちらの場合もコンテンツは共通の JSON にする。

---

## 12. 参考：既存のファン実装（Digital-AFWI）から学べること [ファン]

GitHub `warphoenix32/Digital-AFWI` は、1つの HTML ファイルで動くホットシート版。調査時点のバージョンは 1.4.0 Executive Edition。

- 参考になる点：
  - カードデータを JSON にまとめている
  - 未捕捉の駒を「UNIDENTIFIED TRACK」として隠す（AQ 1 の霧）
  - 応答・選択・目標指定・隠匿情報の受け渡しを、指揮官への明示的な問い合わせとして実装している
  - Playwright による29シナリオの自動テストがある
- 注意点：
  - 入札の勝者を常に先攻に固定している
  - 艦艇を3点耐久にしている
  - これらは公式ガイドの記述と異なるか、公式ガイドで確認できない。そのまま真似ずに §9 の設定値で吸収するのがよい
- 権利関係：本書はそのデータの構造と数値を参照しただけで、コードやデータを複製していない。ゲーム素材（カード画像、ボード画像など）の権利者と利用条件は未確認なので、再配布する前に確認が必要。
