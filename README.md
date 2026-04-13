# AziRouge

AziRouge は、ローグライク向けにダンジョンピースをランダム接続して生成する Paper プラグインです。  
外部 `schematic` / `schem` を WorldEdit 経由で配置し、入口の正対・隣接判定に成功した接続だけを開口します。

## 前提

- Paper 1.21 系
- WorldEdit
- Java 21

`WorldEdit` が無い場合、プラグインは起動しますが schematic 配置は実行できません。

## できること

- 設定で複数テンプレートパターンを指定
- コマンドでテンプレートパターン、初期ピース、ワールド、座標、seed を上書き
- 外部 schematic の配置
- 入口 2 点指定による開口定義
- 入口サイズごとの個別開口
- piece ごとの隣接ピース denylist
- `1x1x2` 接続部へのランダムドア配置
- seed 再現
- 将来拡張用の敵配置ソケット予約
- AI 解析しやすい `key=value` 形式のデバッグログ

## コマンド

### 生成

```text
/azirouge generate
/azirouge generate --patterns=templates/*.yml,templates/boss/*.yml
/azirouge generate --start=start_room --world=dungeon_world --x=100 --y=64 --z=100
/azirouge generate --seed=123456789
/azirouge generate --depth=10
```

指定可能なオプション:

- `--patterns=` テンプレートファイルの glob をカンマ区切りで指定
- `--start=` 初期ピース ID
- `--world=` 生成先ワールド名
- `--x=` `--y=` `--z=` 初期座標
- `--seed=` seed
- `--depth=` その実行だけ最大 depth を上書き

### リロード

```text
/azirouge reload
```

### デバッグ切替

```text
/azirouge debug on
/azirouge debug off
```

### ゲーム内 authoring

piece と entrance は WorldEdit 選択とコマンドだけで更新できます。  
piece の schematic origin は常に WorldEdit 選択範囲の最小 corner へ固定されます。

```text
/azirouge author piece upsert templates/custom.yml room_a schematics/custom/room_a.schem 1.5
/azirouge author entrance upsert templates/custom.yml room_a north_gate NORTH
/azirouge author entrance remove templates/custom.yml room_a north_gate
```

推奨手順:

1. WorldEdit でピース全体を選択する
2. `/azirouge author piece upsert ...` を実行する
3. この時点で piece の origin は選択範囲の最小 corner に固定される
4. 次に入口面を WorldEdit で 2 点選択する
5. `/azirouge author entrance upsert ...` を実行する

`author piece upsert` は `bounds`、`schematic`、`weight` を更新します。  
`author piece upsert` 実行時に piece の最小 corner は補助メタデータとして保存されます。  
`author entrance upsert` はその保存済み corner を自動再利用して選択面を `point1` / `point2` に変換し、bounds 外や開口深さ超過は拒否します。

## config.yml

```yaml
generation:
  template-patterns:
    - templates/*.yml
  start-piece: start_room
  world: world
  origin:
    x: 0
    y: 64
    z: 0
  seed: 123456789
  algorithm:
    max-depth: 8
    branch-chance: 0.45
    entrance-branch-bonus: 0.15
    depth-prediction-multiplier: 1.0
    min-piece-count: 12
    max-piece-count: 24
door:
  enabled: true
  chance: 0.35
  material: SPRUCE_DOOR
debug:
  enabled: false
enemies:
  enabled: false
  mode: reserved
```

### 主要パラメータ

- `max-depth`: 木構造の最大深さ
- `branch-chance`: 分岐の基本強度
- `entrance-branch-bonus`: 利用可能 entrance 数が多いピースほど枝を増やしやすくする補正
- `depth-prediction-multiplier`: depth から求める目標ピース数の倍率
- `min-piece-count` / `max-piece-count`: depth 予測から求めた目標ピース数の下限 / 上限
- `door.enabled`: デフォルト `true`
- `door.chance`: `1x1x2` 入口のドア出現確率
- `door.material`: Bukkit `Material`

目標ピース数は `max-depth`、開始ピースの入口数、テンプレート全体の入口数と重みから予測されます。  
同じ seed なら、同じ depth と同じテンプレート集合で同じ目標数と同じ生成順になります。

## テンプレート YAML

入口は `point1` と `point2` の 2 点指定です。  
向きは `NORTH / EAST / SOUTH / WEST` のいずれかです。  
YAML にタブは使わず、半角スペースでインデントしてください。

```yaml
pieces:
  start_room:
    schematic: schematics/example/start_room.schem
    weight: 1.0
    denied-adjacent-pieces:
      - trap_*
      - boss_hall
    bounds:
      min: [0, 0, 0]
      max: [8, 5, 8]
    entrances:
      - id: north_gate
        facing: NORTH
        point1: [3, 1, 0]
        point2: [5, 3, 0]
      - id: east_door
        facing: EAST
        point1: [8, 1, 3]
        point2: [8, 2, 3]
    enemy-sockets:
      - id: center
        position: [4, 1, 4]
        tag: common
```

`denied-adjacent-pieces` は任意です。  
未指定なら隣接制限なし、指定した場合はその piece は列挙した piece ID に隣接できません。  
`*` ワイルドカードも使えます。どちらか片側でも相手を deny していれば、その隣接は成立しません。
旧キー `allowed-adjacent-pieces` は非対応です。残っている場合はテンプレート読み込み時にエラーになります。

### 入口サイズと開口ルール

- `NORTH/SOUTH` は X 幅と Y 高さから入口サイズを求めます
- `EAST/WEST` は Z 幅と Y 高さから入口サイズを求めます
- 開口奥行きは `width` を使用します
- `3x3` の入口は `3x3x3` を開口します
- `1x2` の入口は `1x1x2` を開口します
- 接続判定は「正対しており、1 ブロック隣接し、入口底面の Y が一致していて、横方向が中央一致しており、スパンが 1 ブロック以上重なっていること」です
- `3x3x3` と `1x1x2` が接続しても、開口はそれぞれのサイズで個別適用され、`1x4x2` のような統合開口にはなりません
- 入口接続は入口底面の Y を一致させ、横方向だけ中央寄せで配置します
- 生成後も全ピースを再走査し、親子接続でなくても隣接していて entrance 条件が合う組は自動で開口します
- `denied-adjacent-pieces` を指定した piece は、deny された piece と面で接する配置自体が拒否されます

## schematic 配置の考え方

- `schematic` はテンプレート YAML からの相対パス、またはプラグインデータフォルダからの相対パス、または絶対パスで指定できます
- 貼り付け時は clipboard origin を最小 corner に正規化したうえで、回転ごとに paste 座標へ補正を入れて min-corner 整合を維持します
- `bounds` と入口座標は最小 corner 基準で合わせてください

## デバッグログ

`/azirouge debug on` または `debug.enabled: true` で有効化できます。

出力例:

```text
debug|scope=schematic|event=paste_begin|piece=start_room|rotation=90|schematic=C:\...\start_room.schem|target=100,64,100
debug|scope=generation|event=candidate_rejected|candidate=corner_room|reason=collision|rotation=180|origin=110,64,95
debug|scope=carve|event=applied|parentBox=100,65,100->102,67,102|childBox=100,65,99->100,66,99
```

## 敵配置について

現状は準備工事段階です。  
`enemy-sockets` はワールド座標へ変換され、生成結果に予約情報として残りますが、実際の Mob スポーン処理はまだ実装していません。

## 開発メモ

- このリポジトリには Gradle wrapper 実体が入っていないため、ビルドにはローカルの `gradle` か wrapper の追加が必要です
- `src/main/resources/templates/example-basic.yml` にサンプル定義があります
