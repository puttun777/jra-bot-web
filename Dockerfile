# 実行イメージ（T2.5 後半）。1 つの Dockerfile から 4 つのイメージをビルドする:
#   docker build --target dashboard|ingest|bot|db-migration --build-arg GIT_SHA=<40桁> -t jra-bot/<target>:<git sha> .
# - 全ターゲットが同じ `build` ステージ（Gradle Wrapper で bootJar / installDist）を共有するので、4 つ続けてビルドしても Gradle は 1 回だけ。
# - 実行イメージは JRE だけ（JDK・Gradle・ソース・テストを含まない）。root 以外（uid 10001）で動く。
# - ENTRYPOINT は exec 形式の `java` を PID 1 にする。SIGTERM は JVM がそのまま受け、終了コードはアプリの終了コードのまま返る（シェルを挟まない）。
# - OCI ラベル revision / version は Git SHA（ベースイメージの version=24.04 を上書きする）。リリース番号は release-manifest が持つ。
# - ログは標準出力・標準エラーだけ。ファイルへは書かない。秘密・実データ・認証情報はイメージに入れない（.dockerignore で文脈から除外）。
# - HEALTHCHECK は付けない: Cloud Run は Dockerfile の HEALTHCHECK を使わず、サービス側のプローブ（/internal/health 等。infra が設定）を使う。
#   Job（bot / db-migration）は常駐しないので不要。
# - REQUIRED_SCHEMA_VERSION は各アプリの build.gradle.kts → jar の jrabot-app.properties へ焼き込まれる（環境変数では上書きしない: CONTRACTS §2・§4.3）。
# - ベースイメージは digest 固定。更新は Dependabot（docker）が digest を更新する PR を出す（自動マージしない）。タグは JDK 25 の LTS 系列。
# - GCP・レジストリへの push は行わない（ここはビルド定義だけ）。

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
ARG GIT_SHA=unknown
# テストと lint は CI の別ジョブで実行済み（イメージのビルドでは実行しない）。ここでは成果物だけを作る
# 依存のダウンロード先（~/.gradle/caches）は BuildKit のキャッシュマウント: 層には残らず（イメージに入らない）、同じビルダー上の再ビルドで再利用される
RUN --mount=type=cache,target=/root/.gradle/caches,id=jrabot-gradle-caches \
    ./gradlew --no-daemon --console=plain --stacktrace -PreleaseId="${GIT_SHA}" \
        :bot:bootJar :ingest:bootJar :dashboard:bootJar :db-migration:installDist

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
# 1 イメージで `--task=bot-run|calendar-check|reconcile|retention|export-dataset` を切り替える（CONTRACTS §1）。
# Cloud Run Jobs の args に `--task=… --tenant-id=…` を渡す（ENTRYPOINT は変えない）。業務処理はスタブで、本番(APP_ENV=prod)では成功にならない。
FROM runtime-base AS bot
COPY --from=build --chmod=0444 /src/bot/build/libs/bot.jar /app/app.jar
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jra-bot bot" \
      org.opencontainers.image.description="JRA Bot Job (Spring Boot). Switch with --task. Task logic is not implemented yet (stubs)" \
      org.opencontainers.image.source="https://github.com/puttun777/jra-bot-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

# ---------------------------------------------------------------- db-migration
# Flyway CLI ラッパ。`--mode=info|validate|migrate`（CONTRACTS §4.2）。標準出力は JSON 1 行だけ。ModeProvider は参照しない。
FROM runtime-base AS db-migration
COPY --from=build --chmod=0444 /src/db-migration/build/install/db-migration/lib /app/lib
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jra-bot db-migration" \
      org.opencontainers.image.description="JRA Bot Flyway CLI wrapper (--mode=info|validate|migrate)" \
      org.opencontainers.image.source="https://github.com/puttun777/jra-bot-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "jp.jrabot.dbmigration.DbMigrationMainKt"]
