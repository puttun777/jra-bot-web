# legacy-import（旧 SQLite → MySQL の移行ツール。D-271）

利用者の PC で、旧実装（参照元 jra-bot）の `data/jraev.sqlite` の**コピー**に対して実行する CLI。アプリのイメージには入らない。今は `preflight` だけ。

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
