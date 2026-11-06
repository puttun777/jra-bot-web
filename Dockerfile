# 実行イメージ（T2.5 後半 + T3.6-B）。1 つの Dockerfile から 5 つのイメージをビルドする:
#   docker build --target dashboard|ingest|bot|db-migration|ml-train --build-arg GIT_SHA=<40桁> -t jra-bot/<target>:<git sha> .
# - 5 つのターゲットはすべて JVM で、同じ `build` ステージ（Gradle Wrapper で bootJar / installDist）を共有するので、続けてビルドしても Gradle は 1 回だけ。
#   ml-train も Kotlin（D-257。旧 Python の ml/ は削除）。
# - 実行イメージは JRE だけ（JDK・Gradle・ソース・テストを含まない）。root 以外（uid 10001）で動く。
# - ENTRYPOINT は exec 形式の `java` を PID 1 にする。SIGTERM は JVM がそのまま受け、終了コードはアプリの終了コードのまま返る（シェルを挟まない）。
# - OCI ラベル revision / version は Git SHA（ベースイメージの version=24.04 を上書きする）。リリース番号は release-manifest が持つ。
# - ログは標準出力・標準エラーだけ。ファイルへは書かない。秘密・実データ・認証情報はイメージに入れない（.dockerignore で文脈から除外）。
# - HEALTHCHECK は付けない: Cloud Run は Dockerfile の HEALTHCHECK を使わず、サービス側のプローブ（/internal/health 等。infra が設定）を使う。
#   Job（bot / db-migration）は常駐しないので不要。
# - REQUIRED_SCHEMA_VERSION は各アプリの build.gradle.kts → jar の jrabot-app.properties へ焼き込まれる（環境変数では上書きしない: CONTRACTS §2・§4.3）。
# - ベースイメージは digest 固定。更新は Dependabot（docker）が digest を更新する PR を出す（自動マージしない）。タグは JDK 25 の LTS 系列。
# - GCP・レジストリへの push は行わない（ここはビルド定義だけ）。

# ---------------------------------------------------------------- frontend
# ダッシュボードの画面（TypeScript。D-263）を ES モジュールへ変換する。Gradle の compileFrontend と同じ tsc・同じ tsconfig（版は package-lock.json）。
# Node は CI（actions/setup-node）と同じ 24.22.2。結果だけを build ステージへ渡す（Node・node_modules は実行イメージに入らない）。
FROM node:24.22.2-trixie-slim@sha256:54f84b4cf8432b21aaffd5d3ce8d41e12cea0d10d33c09d2f3c9879d49d6b446 AS frontend
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm,id=jrabot-npm-cache \
    npm ci --ignore-scripts --no-audit --no-fund
COPY dashboard/tsconfig.json ./dashboard/
COPY dashboard/src/main/ts ./dashboard/src/main/ts
RUN node node_modules/typescript/bin/tsc -p dashboard/tsconfig.json \
    && test -f dashboard/build/generated/frontend/static/js/app.js

# ---------------------------------------------------------------- build
FROM eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build
WORKDIR /src
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dfile.encoding=UTF-8"
# 1) Gradle Wrapper の取得（sha256 検証付き: gradle-wrapper.properties）。ビルド定義が変わらない限りこの層を再利用する
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY build-logic ./build-logic
RUN ./gradlew --no-daemon --console=plain --version
# 2) ソース（.dockerignore で必要なものだけ）。リリース ID は jar の jrabot-app.properties に焼き込む
COPY domain ./domain
COPY persistence ./persistence
COPY cloud-gcp ./cloud-gcp
COPY db-migration ./db-migration
COPY bot ./bot
COPY ingest ./ingest
COPY dashboard ./dashboard
COPY ml-train ./ml-train
# settings.gradle.kts が include するため必要（検証専用のツールで、どのイメージにも入らない: jar は上の 4 つのターゲットの成果物だけをコピーする）
COPY golden-runner ./golden-runner
# 旧 SQLite の移行ツール（D-271）。settings.gradle.kts が include するため必要（どのイメージにも入らない）
COPY legacy-import ./legacy-import
# 画面の変換結果（frontend ステージ）。-PfrontendPrebuilt=true で Gradle の compileFrontend（Node が要る）を省き、この結果を jar に入れる
COPY --from=frontend /src/dashboard/build/generated/frontend ./dashboard/build/generated/frontend
ARG GIT_SHA=unknown
# テストと lint は CI の別ジョブで実行済み（イメージのビルドでは実行しない）。ここでは成果物だけを作る
# 依存のダウンロード先（~/.gradle/caches）は BuildKit のキャッシュマウント: 層には残らず（イメージに入らない）、同じビルダー上の再ビルドで再利用される
RUN --mount=type=cache,target=/root/.gradle/caches,id=jrabot-gradle-caches \
    ./gradlew --no-daemon --console=plain --stacktrace -PreleaseId="${GIT_SHA}" -PfrontendPrebuilt=true \
        :bot:bootJar :ingest:bootJar :dashboard:bootJar :ml-train:bootJar :db-migration:installDist

# ---------------------------------------------------------------- runtime base
FROM eclipse-temurin:25-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5 AS runtime-base
# uid/gid 10001 の専用ユーザー（ログイン不可・ホーム無し）。アプリは /app を読むだけで書かない（一時ファイルは /tmp）
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app
ENV TZ=Asia/Tokyo
WORKDIR /app
USER 10001:10001

# ---------------------------------------------------------------- dashboard
FROM runtime-base AS dashboard
COPY --from=build --chmod=0444 /src/dashboard/build/libs/dashboard.jar /app/app.jar
EXPOSE 8080
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jra-bot dashboard" \
      org.opencontainers.image.description="JRA Bot dashboard (Spring Boot). Web UI and /api/v1" \
      org.opencontainers.image.source="https://github.com/puttun777/jra-bot-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

# ---------------------------------------------------------------- ingest
FROM runtime-base AS ingest
COPY --from=build --chmod=0444 /src/ingest/build/libs/ingest.jar /app/app.jar
EXPOSE 8080
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jra-bot ingest" \
      org.opencontainers.image.description="JRA Bot ingest (Spring Boot). Windows agent receiver" \
      org.opencontainers.image.source="https://github.com/puttun777/jra-bot-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

# ---------------------------------------------------------------- bot
# 1 イメージで `--task=bot-run|calendar-check|reconcile|retention|export-dataset|backtest` を切り替える（CONTRACTS §1）。
# Cloud Run Jobs の args に `--task=… --tenant-id=…` を渡す（ENTRYPOINT は変えない）。すべてのタスクが実装済み（backtest は D-265）。
FROM runtime-base AS bot
COPY --from=build --chmod=0444 /src/bot/build/libs/bot.jar /app/app.jar
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jra-bot bot" \
      org.opencontainers.image.description="JRA Bot Job (Spring Boot). Switch with --task. Tasks: bot-run, calendar-check, reconcile, retention, export-dataset, backtest" \
      org.opencontainers.image.source="https://github.com/puttun777/jra-bot-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

# ---------------------------------------------------------------- db-migration
# Flyway CLI ラッパ。`--mode=info|validate|migrate`（CONTRACTS §4.2）。標準出力は JSON 1 行だけ。ModeProvider は参照しない。
FROM runtime-base AS db-migration
COPY --from=build --chmod=a=rX /src/db-migration/build/install/db-migration/lib /app/lib
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jra-bot db-migration" \
      org.opencontainers.image.description="JRA Bot Flyway CLI wrapper (--mode=info|validate|migrate)" \
      org.opencontainers.image.source="https://github.com/puttun777/jra-bot-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "jp.jrabot.dbmigration.DbMigrationMainKt"]

# ---------------------------------------------------------------- ml-train
# ML 学習 Job（D-257。ML_DESIGN §4.2・§16・CONTRACTS §5.1）。常駐しない。必要時に Cloud Run Job として `args` で `train ...` を渡す。
# - 特徴量は再計算しない（Kotlin の export-dataset が作る）。DB は `mltrain`（ml_model の SELECT/INSERT のみ）。登録は CANDIDATE / REJECTED だけ（ACTIVE にしない）。
#   実データ・学習済みモデル・秘密鍵はイメージに入れない（MODEL_SIGNING_KEY は実行時の Secret）。convert-pkl は利用者のローカル専用（クラウドの環境では拒否する）。
# - SIGTERM はアプリが受け（JVM の既定の即終了にしない）、確定（レジストリ登録）の前なら成果物を片付けて 75 で終わる。終了コードは CONTRACTS §9.1。
# - 計算は単一スレッドで決定的（乱数なし）。ログは標準出力の JSON 1 行。
FROM runtime-base AS ml-train
COPY --from=build --chmod=0444 /src/ml-train/build/libs/ml-train.jar /app/app.jar
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jra-bot ml-train" \
      org.opencontainers.image.description="JRA Bot ML training Job (Kotlin). Registers CANDIDATE/REJECTED models only; never promotes to ACTIVE" \
      org.opencontainers.image.source="https://github.com/puttun777/jra-bot-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
