# legacy-import（旧 SQLite → MySQL の移行ツール。D-271）

利用者の PC で、旧実装（参照元 jra-bot）の `data/jraev.sqlite` の**コピー**に対して実行する CLI。アプリのイメージには入らない。今は `preflight`・`extract`・`verify-extract`・`transform`・`load`。

## コピーの作り方（重要）
旧アプリは WAL のモードで書く。**旧アプリ（デーモン・ダッシュボード）を止めてから**ファイルをコピーするか、`sqlite3 data/jraev.sqlite ".backup jraev-copy.sqlite"` で 1 ファイルのコピーを作る。
横に中身のある `-wal`・`-shm` が残ったコピーは受け付けない（終了コード 2）。元のファイルは消さない。

## 実行
```
./gradlew :legacy-import:installDist
legacy-import/build/install/legacy-import/bin/legacy-import preflight --sqlite <コピー> --out preflight-report.json
```
Windows は `legacy-import\build\install\legacy-import\bin\legacy-import.bat`。`--out` は既にあるファイル・入力と同じパスを受け付けない。

| 終了コード | 意味 |
|---|---|
| 0 | レポートを出した（OK または WARN。WARN の理由は標準エラーとレポートの `warnings`） |
| 1 | NG（整合性の検査の失敗・期待する表が無い・抽出に要る列が無い） |
| 2 | 引数・入力の誤り・ツールの失敗（レポートは無い） |

レポートは件数と表・列の名前だけで、値（馬名・レース ID・JSON の中身）は含まない。それでも**実データ由来のファイル**として扱い、Git に入れない。

## 抽出（extract。D-273）
```
legacy-import/build/install/legacy-import/bin/legacy-import extract --sqlite <コピー> --out-dir <無いか空のディレクトリ> [--chunk-rows 10000]
legacy-import/build/install/legacy-import/bin/legacy-import verify-extract --dir <同じディレクトリ>
```
- 先に preflight を行い、NG なら抽出しない（終了コード 1）。WARN は抽出する（期待に無い表・列は出さない）。
- 出力: `<表>/<番号>.ndjson.gz`（1 行 = 列の値の JSON 配列。主キーの順）と `manifest.json`（列・行数・主キーの範囲・内容の sha256・入力の sha256）。**値そのものを含む実データ**なので、Git・クラウドに置かない（クラウドへの投入は COMP-4 の回答待ち）。
- 途中で失敗したら出力先を消して再実行する（抽出は決定的で、同じ入力から同じ内容になる）。`manifest.json` が無い出力先は不完全。
- `verify-extract`: 各チャンクを読み直して manifest と照らす（0 = 一致、1 = 食い違い、2 = 検証できない）。

## 変換（transform。D-274）
```
legacy-import/build/install/legacy-import/bin/legacy-import transform --extract <extract の出力先> --out-dir <無いか空のディレクトリ> --tenant-id <n> [--naive-zone Asia/Tokyo] [--lenient-json]
```
- extract のチャンクを sha256 で照合しながら読み、新しいスキーマの値に変換する（DB には触れない）。変換の表は DATA_MIGRATION_DESIGN §5.5 にある。
- 変換できない行は `quarantine/<旧表>.ndjson.gz` に、理由・主キー・元の値と一緒に書く。親が隔離された子も隔離する。
- 表ごとに「元の行数 = 変換 + 隔離」を `transform-manifest.json` に記録する。標準エラーには件数と理由のコードだけを出す。
- 旧の未通知のシグナル（`sent=0`）は SEND_UNKNOWN にする。新しい bot が古い通知を送らないようにするため。
- 時刻の解釈（`--naive-zone`）は、preflight の時刻の形式の内訳を見て本人が決める。

## 読込み（load。D-275）
```
LEGACY_IMPORT_DB_PASSWORD=... legacy-import/build/install/legacy-import/bin/legacy-import load \
  --transform <transform の出力先> --jdbc-url jdbc:mysql://localhost:3306/<db> --user <user>
```
- 接続先は**利用者の PC の MySQL だけ**（COMP-4: クラウドへの投入は回答まで行わない）。
  - host は localhost・127.0.0.1・[::1] の 1 つだけ。URL のパラメータは sslMode・useSSL・allowPublicKeyRetrieval・connectTimeout・socketTimeout・characterEncoding だけ（SOCKS・名前付きパイプ・ソケットの差し替えなど、接続の経路を変えるものは拒否）。
  - 接続後に Cloud SQL の変数（`cloudsql*`）があれば拒否する（Cloud SQL が持つ変数を使う経験則。実インスタンスでは未確認）。
  - **注意**: Cloud SQL Auth Proxy・IAP・`ssh -L` などのトンネルは localhost に見え、Cloud SQL 以外のクラウドの MySQL は検出できない。クラウドの DB に向けたトンネルを開いたまま実行しない。
- Flyway の V1〜V5 を適用し、`tenant` に transform の `--tenant-id` の行を入れておく。パスワードは引数に取らず、環境変数 `LEGACY_IMPORT_DB_PASSWORD` から読む。
- 新しい実行は、そのテナントの対象表が空の DB にだけ読み込む。読み込んだチャンクは、データと同じトランザクションで DB の台帳（`legacy_import_chunk`）に記録する。途中で止まったら同じコマンドを再実行すると続きから進む。すべての検証に通ったら台帳を消す。読込み済みの DB への再実行は拒否する。
- 隔離した行は `import_quarantine` に入る（`migration_run` は transform の manifest の sha256）。
- 途中まで読み込んだ後に transform をやり直す（`--naive-zone` を変える等）と manifest の sha256 が変わり、その DB には入れない（『別の transform』）。DB を作り直してから読み込む。
- 最後に表ごとの件数を transform の manifest と照らす。終了コード: 0 = 一致、1 = 違う（台帳は調べられるように残す）、2 = 入力・接続・DB の失敗。
