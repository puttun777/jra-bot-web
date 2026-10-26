# NOTICE（権利表示）

このリポジトリにはライセンスを付与していません（All rights reserved。`README.md`）。以下は、**既存の著作権・許諾表示を保持するための表示**であり、リポジトリ全体への許諾ではありません。確認が済んでいない権利関係は「未確認」と書き、問題がないとは断定しません。

## 旧実装 `puttun777/jra-bot`（MIT）
- 旧実装（Python）は MIT License、Copyright (c) 2026 puttun777（同一の権利者。固定コミット `f7dbf69fd8940121782870de902e8c449e6ca149` の `LICENSE` で確認）。
- 本リポジトリの Kotlin 実装・Python ツールは、旧実装の挙動を**再実装**したもの（コードの大量複製はしない: D-004）。`tools/golden` のフィクスチャは旧実装を固定コミットで実行して得た**合成入力の出力**。旧実装のコードは取り込まず、CI が固定コミットを取得して実行する。
- MIT の条件（著作権表示と許諾表示を、複製・実質的部分に含める）に従い、旧実装に由来する部分には次の表示を保つ:

  > MIT License — Copyright (c) 2026 puttun777
  > Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, ... （全文は https://github.com/puttun777/jra-bot の `LICENSE`）

## 第三者のソフトウェア
- 本リポジトリが**同梱**する第三者ファイルは `gradle/wrapper/gradle-wrapper.jar`（Gradle Wrapper。Apache License 2.0、Copyright Gradle Inc. and contributors）だけ。ライセンス: https://github.com/gradle/gradle/blob/master/LICENSE
- 他の依存（Spring Boot・Doma・Flyway・MySQL Connector/J・google-cloud-storage・Jackson・numpy・cryptography 等）は**ビルド時に取得**し、このリポジトリには含めない。版は `gradle/libs.versions.toml`・`*/requirements*.txt`・`docs/VERSIONS.md`。各ライセンスの条件（MySQL Connector/J は GPLv2 + FOSS Exception など）は、**イメージを配布する場合に別途確認が必要**（現在はイメージを公開レジストリへ push しない）。個別ライセンスの精査は**未実施**。
- Docker のベースイメージ（eclipse-temurin、python、mysql）は各提供元のライセンスに従う。リポジトリには含めない。

## JRA-VAN Data Lab.
- JRA-VAN のデータ・JV-Link SDK・DLL・仕様書は含まない（`.gitignore`・`repo-hygiene`）。名称 JRA-VAN・JV-Link は各権利者のもの。
- ソースコードの Public 公開について JRA-VAN の許可は**得ていない**（`docs/UNRESOLVED.md` E-7）。
