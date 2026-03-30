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
5. その最小 corner ブロックへ立ったまま `/azirouge author entrance upsert ...` を実行する

`author piece upsert` は `bounds`、`schematic`、`weight` を更新します。  
`author entrance upsert` は選択面を `point1` / `point2` として保存し、bounds 外や開口深さ超過は拒否します。

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
    target-piece-count: 18
    piece-count-jitter: 2
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
- `branch-chance`: 主経路以外の入口を枝として使う確率
- `target-piece-count`: 目標ピース数
- `piece-count-jitter`: seed ごとの軽微な揺らぎ
- `min-piece-count` / `max-piece-count`: seed による極端な上下振れを抑える範囲
- `door.enabled`: デフォルト `true`
- `door.chance`: `1x1x2` 入口のドア出現確率
- `door.material`: Bukkit `Material`

## テンプレート YAML

入口は `point1` と `point2` の 2 点指定です。  
向きは `NORTH / EAST / SOUTH / WEST` のいずれかです。  
YAML にタブは使わず、半角スペースでインデントしてください。

```yaml
pieces:
  start_room:
    schematic: schematics/example/start_room.schem
    weight: 1.0
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

### 入口サイズと開口ルール

- `NORTH/SOUTH` は X 幅と Y 高さから入口サイズを求めます
- `EAST/WEST` は Z 幅と Y 高さから入口サイズを求めます
- 開口奥行きは `width` を使用します
- `3x3` の入口は `3x3x3` を開口します
- `1x2` の入口は `1x1x2` を開口します
- 接続判定は「正対しており、1 ブロック隣接し、小さい側の入口面が完全に相手面へ接していること」です
- `3x3x3` と `1x1x2` が接続しても、開口はそれぞれのサイズで個別適用され、`1x4x2` のような統合開口にはなりません

## schematic 配置の考え方

- `schematic` はテンプレート YAML からの相対パス、またはプラグインデータフォルダからの相対パス、または絶対パスで指定できます
- 貼り付け時は WorldEdit clipboard origin をそのまま使わず、clipboard の最小 corner を実質的な原点として補正します
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
