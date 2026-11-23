# 実機なし検証（Pre-delivery UI verification）

このプロジェクトの交付前チェックの正本。方針は AGENTS.md の
「Pre-delivery UI verification standard — no emulator」：この VM には
KVM が無くエミュレータは使えないので、画面は JVM 上で描いて確かめる。
身体感覚が要る確認（カメラの見え方、ピンの位置一致、TalkBack の
読み上げ等）は従来どおり実機待ち行列の領分で、ここでは扱わない。

## 何が入っているか

| 層 | 道具 | 対象 |
|---|---|---|
| スクリーンショットテスト | Robolectric 4.14.1（GraphicsMode NATIVE）＋ Roborazzi 1.35.0 | 主要4画面＋結果シート（下表） |
| 画面遷移・状態保持テスト | Robolectric ＋ Compose テスト（`MainActivityNavigationTest`） | 実 MainActivity のタブ遷移、作る画面の下書き保持（R2-6） |
| 純粋ロジック単体テスト | JUnit 4（従来どおり） | CodeClassifier / QrFactory / Store の剪定（42件） |

バージョンはこの VM のミラーが持つものに固定した
（Robolectric 4.14.1 = SDK 35 の android-all、compose BOM 2024.12.01
の ui-test-junit4 1.7.6）。

### スクリーンショットのカバー範囲

`app/src/test/java/app/qrikkiyomi/ScreenScreenshotTest.kt`、
golden は `app/src/test/screenshots/`。

| ファイル | 画面・状態 |
|---|---|
| `scan_permission.png` | スキャン：カメラ許可前の案内（起動直後の画面） |
| `scan_image_mode.png` | スキャン：画像モード（画像＋ピン2件＋結果リスト、Wi-Fi パスワード伏字を含む） |
| `create_url.png` | 作る：URL 入力済み（ライブプレビュー付き） |
| `library_empty.png` | 宝箱：0件の空案内 |
| `library_entries.png` | 宝箱：作成1件＋読取2件（お気に入り・フォルダ・メモあり） |
| `settings.png` | 設定：初期値＋保存3件 |
| `result_short_url.png` | 結果シート：短縮 URL（ホスト確認＋警告バナー） |

- オンボーディングはこのアプリには無い（初回ヒントはカメラ許可後の
  スキャン画面上に1行出るだけ）。代替として、初回起動で必ず出る
  「許可前の案内状態」をカバーしている。ヒント自体はカメラが
  ライブでないと出ないため JVM では描けず、未カバーのままである。
- カメラのライブ映像そのもの（プレビュー＋実検出ピン）は実カメラが
  要るので JVM では描けない。画像モードで「ピン＋リスト」の描画は
  カバーしている。

## 実行方法

```bash
./run-tests.sh          # 全部実行。スクショは golden と照合（verify）
./run-tests.sh record   # golden を撮り直す（画面を変えたら実行し、目視してから確定）
```

- 交付前チェックとして、APK を渡す前に必ず `./run-tests.sh`（verify）
  を通すこと。これが今後の標準手順。
- golden を撮り直したら、画像を実際に開いて崩れ・はみ出しが無いか
  目視してから verify が通ることを確認する（撮りっぱなしで通さない）。

### なぜ `gradle test` ではないか（この VM の事情）

- Gradle Test Executor のワーカーデーモンは loopback TCP で通信するが、
  この VM では横取りされて動かない。そこで Gradle でコンパイルまで
  行い、AGP が組み立てたテスト用クラスパス
  （`:app:writeTestRuntimeClasspath` が `app/build/test-runtime-classpath.txt`
  に書き出す）を JUnitCore で直接実行する（従来の純粋テストと同じ方式）。
- 依存解決は `QR_FILE_MIRROR=1` のときミラーのディスクキャッシュを
  file リポジトリとして `--offline` で読む（settings.gradle.kts）。
  HTTP ミラーは複数ビルドで共有すると詰まるための回避策で、通常の
  ビルド（build.sh）は従来どおり HTTP ミラーを使う。
  新しいテスト依存を足すときは、先に curl でキャッシュへ取得する
  （AGENTS.md の file:// ミラーキャッシュ方式）。
- Robolectric の android-all jar は、Java から直接ダウンロード
  できないこの VM ではミラーキャッシュの jar を
  `app/build/robolectric-deps/` へコピーして
  `robolectric.offline=true` で読む。通常のマシンでは Robolectric が
  Maven Central から自動取得するので、この仕掛けは要らない。
- GRADLE_USER_HOME は共有の `~/workspace/tools/gradle-home` ではなく
  プロジェクト内 `.local/gradle-home` を使う（共有ホームのジャーナル
  ロックが他ビルドと競合するため）。
- ビルド直列化ルール（AGENTS.md、2026-11-23）：VM 全体の Gradle／
  Flutter ビルドは同時2本まで。build.sh と run-tests.sh の Gradle
  起動はすべて `~/workspace/tools/with-build-lock.sh`（flock 2枠）
  経由が標準。横展開時は他プロジェクトと順番待ちになるため、
  実行には時間がかかることがある（2026-11-23 の初回セットアップでは
  混雑のため各ランに 10〜30 分を要した）。

### ハマりどころ（2026-11-23 のセットアップで踏んだもの）

- `:app:writeTestRuntimeClasspath`：Test タスクの classpath を
  `inputs.files` に渡すと testDebugUnitTest 自体がタスクグラフに
  入り、動かない Test Executor が実行されて失敗する。compile タスクへ
  明示的に dependsOn し、classpath は doLast で遅延読みすること。
- 純粋テストの org.json は android.jar のスタブ（"not mocked"）に
  当たるため、`org.json:json:20240303` を testImplementation に
  入れて実物を先行させる。
- ML Kit は Robolectric では初期化プロバイダが動かず、ScanScreen の
  組成で `MlKitContext has not been initialized` になる。テスト専用
  Application（`QrTestApplication`）の onCreate で `MlKit.initialize`
  する。サンドボックス再利用で2回目以降は「already initialized」に
  なるので、その例外は握って冪等にする。
- Compose テストルールの ComponentActivity はマニフェストに無いため
  Robolectric が解決できない。スクショは Roborazzi の composable 版
  `captureRoboImage(file) { … }`（自前の登録済み Activity を使う）を
  直接呼ぶ。RoborazziRule 方式は使わない（他プロジェクトの実績でも
  captureRoot 必須エラーで失敗している）。
- スクショ撮影時はテーマ背景の Surface で包むこと。実画面は
  MainActivity の Scaffold が content color を供給するが、単体撮影では
  LocalContentColor が黒のままになり、色指定のない Text が
  黒背景に黒で描かれて見えなくなる（実機の不具合ではない）。
  あわせて修飾子 `-night` でダーク配色に固定し golden を安定させる。

## 直近の実行結果

2026-11-23 初回セットアップ（v0.4.0 のコードに対して実施）：

- `./run-tests.sh record`：**全52件合格**（既存42件＋スクショ7件＋
  遷移3件）。内訳：CodeClassifierTest 21件／QrFactoryTest 16件／
  StorePruneTest 5件／ScreenScreenshotTest 7件／
  MainActivityNavigationTest 3件。所要 94 秒（JUnitCore 部分）。
- golden 7枚を生成し、全枚を目視確認した：レイアウト崩壊・
  はみ出し・見切れなし。確認できた要点は、スキャン許可案内の文と
  ボタン、画像モードのピン2件と検出リスト（Wi-Fi は
  `P:••••` と伏字表示）、作る画面の QR ライブプレビューと
  「example.com」見出し、宝箱の空案内文と3件の行（見出し・値・
  日付・お気に入り星）、設定の全項目（タイトル・説明・スイッチ・
  チップ・保存件数3件・バージョン表記 0.4.0 (5)）、結果シートの
  「開く前に確認 bit.ly」見出しと短縮URL警告バナー。
- `./run-tests.sh`（verify）：**全52件合格**（golden 7枚すべて照合
  一致）。同ランの Gradle ステップで assembleDebug も
  BUILD SUCCESSFUL。JUnitCore 部分の所要 79 秒。
  → 初回セットアップ完了。以後の交付前チェックは verify を正とする。
- 未カバーのまま残るもの：カメラのライブプレビュー状態と初回
  ヒント行（実カメラ必須）、ライト配色での見た目（golden はダーク
  固定）、TalkBack 読み上げ、Wi-Fi 実接続。これらは従来どおり
  実機待ち行列の領分。

