# wargame

AFWI（Air Force Wargame: Indo-Pacific）の調査資料と、そのルールで遊べる Android アプリ（非公式）。

## 文書

| 文書 | 内容 |
|---|---|
| [docs/afwi-rules-spec.md](docs/afwi-rules-spec.md) | 遊び方の仕様書。記述ごとに情報源の信頼度と、未確認事項の一覧を付けている |
| [docs/afwi-implementation-design.md](docs/afwi-implementation-design.md) | プログラムで再現するための設計。データモデル、隠し情報、状態機械、カード効果、AI、テスト |

## アプリの構成

| モジュール | 内容 |
|---|---|
| `engine/` | ルールエンジン（純粋な Kotlin、Android に依存しない）。カード・機種データは `engine/src/main/resources/afwi/content.json` |
| `ai/` | CPU の対戦相手（純粋な Kotlin）。自分の視点だけを見て合法手から選ぶ。初級・中級の2段階 |
| `app/` | Android アプリ（Jetpack Compose）。CPU 対戦とホットシート |

現在は M5（第1版の候補）です。用意されたキャンペーン5本（遭遇戦・大会・長期戦・世界が見ている・予備戦力）を、CPU 対戦（米・中どちらでも、初級・中級）かホットシートで遊べます。

- 対局は自動保存され、アプリを閉じても続きから遊べる
- 終わった対局は、隠し情報をすべて開いた状態で1手ずつリプレイできる
- ホーム画面の「ルール解説」でルールの要約を読める
- 未確認のルールは「ルール設定」で選べる

遊べる範囲は次のとおりです。

- 秘密のミッション選択と、7種すべてのミッションの採点
- 態勢の選択（標準以外は再使用不可）と、全20飛行隊からのドラフト
- 支援カード68枚（選択・入札への差し出し・情報判定での公開・手番中の使用）
- 応答カードとミサイル防衛の割り込み（使える側に該当カードや艦艇があるときだけ問い合わせる）
- 配置（基地か CL）、主導権の入札と先攻の決定
- 飛行隊の起動、移動・捕捉・射撃、弾切れと帰投
- 早期警戒機、爆撃機、防空、無人機、艦艇、空母艦載機、EC-130
- サイバー値（4で即勝利）と、ATO をまたぐ損耗の持ち越し

## ビルド

ルールエンジンと AI のテストは JDK 17 以上があれば実行できます（Android SDK は不要）。

```bash
./gradlew :engine:test :ai:test
```

APK のビルドには Android SDK が必要です。`ANDROID_HOME` を設定するか `local.properties` に `sdk.dir` を書くと、`:app` モジュールが有効になります。

```bash
./gradlew :app:assembleDebug
```

GitHub Actions（`.github/workflows/android.yml`）が、push のたびにテストとデバッグ APK のビルドを行います。APK は実行結果の Artifacts（`afwi-debug-apk`）からダウンロードできます。

## インストール

1. Artifacts からダウンロードした zip を展開し、`app-debug.apk` を端末に移す。
2. 端末の設定で、ファイルを開くアプリに「不明なアプリのインストール」を許可する。
3. APK を開いてインストールする。

## 配布用 APK（署名付き）

`v` で始まるタグ（例: `v1.0.0`）を push すると、GitHub Actions が配布用 APK を作り、GitHub のリリースに添付します。タグを付けない push でも、配布用 APK は Artifacts（`afwi-release-apk`）に出ます。

署名鍵を登録していない場合、配布用 APK はデバッグ鍵で署名されます。インストールはできますが、別の端末で作った版の上には更新できません。自分の鍵で署名するには、次の手順で鍵を作って GitHub の Secrets に登録します。

1. 鍵を作る（パスワードと別名は自分で決める。鍵ファイルは失くすと更新版を配れなくなるので、2か所に保管する）。

    ```bash
    keytool -genkeypair -v -keystore afwi.jks -alias afwi -keyalg RSA -keysize 2048 -validity 10000
    ```

2. 鍵ファイルを1行の文字列にする。

    ```bash
    base64 -w0 afwi.jks > afwi.jks.b64
    ```

3. リポジトリの Settings → Secrets and variables → Actions に、次の4つを登録する。

    | 名前 | 中身 |
    |---|---|
    | `AFWI_KEYSTORE_BASE64` | `afwi.jks.b64` の中身 |
    | `AFWI_KEYSTORE_PASSWORD` | 鍵ストアのパスワード |
    | `AFWI_KEY_ALIAS` | 鍵の別名（上の例では `afwi`） |
    | `AFWI_KEY_PASSWORD` | 鍵のパスワード |

## AI の調整

CPU の評価の重みは `ai/src/main/kotlin/afwi/ai/Agents.kt` の `Weights` にあります。候補を `ai/src/test/kotlin/afwi/ai/TuningTest.kt` に並べ、次のコマンドで既定値の AI と対戦させて比べられます（時間がかかります）。

```bash
./gradlew :ai:tuning
```

