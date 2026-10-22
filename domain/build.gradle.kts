plugins {
    id("jrabot.kotlin-module")
}

// :domain は Spring / Doma / JDBC / クラウド SDK に依存しない（checkModuleDependencies が強制）。
