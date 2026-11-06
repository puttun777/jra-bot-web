plugins {
    id("jrabot.kotlin-module")
    application
}

// 旧 SQLite（参照元 jra-bot の data/jraev.sqlite のコピー）→ MySQL の移行ツール（D-271。DATA_MIGRATION_DESIGN §5）。
// 利用者の PC で実行する CLI で、アプリのイメージには含めない。今の段階は preflight（読取り専用の事前検査とレポート）だけ。
// 実データは Git に入れない。試験は合成の SQLite だけ（COMP-4: 実データのクラウド投入は回答待ち）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.sqlite.jdbc)
}

application {
    mainClass.set("jp.jrabot.legacyimport.LegacyImportKt")
}
