# 実行イメージ（T2.5 後半 + T3.6-B）。1 つの Dockerfile から 5 つのイメージをビルドする:
#   docker build --target dashboard|ingest|bot|db-migration|ml-train --build-arg GIT_SHA=<40桁> -t jra-bot/<target>:<git sha> .
# - JVM の 4 ターゲットは同じ `build` ステージ（Gradle Wrapper で bootJar / installDist）を共有するので、続けてビルドしても Gradle は 1 回だけ。
#   ml-train は Python（独立した依存環境。Gradle を使わない。下の ml-train ステージ）。
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
# settings.gradle.kts が include するため必要（検証専用のツールで、どのイメージにも入らない: jar は上の 4 つのターゲットの成果物だけをコピーする）
COPY golden-runner ./golden-runner
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
COPY --from=build --chmod=a=rX /src/db-migration/build/install/db-migration/lib /app/lib
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jra-bot db-migration" \
      org.opencontainers.image.description="JRA Bot Flyway CLI wrapper (--mode=info|validate|migrate)" \
      org.opencontainers.image.source="https://github.com/puttun777/jra-bot-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/lib/*", "jp.jrabot.dbmigration.DbMigrationMainKt"]

# ---------------------------------------------------------------- ml-train (Python)
# ML 学習 Job（T3.6-B。ML_DESIGN §4.2・CONTRACTS §5.1）。常駐しない。必要時に Cloud Run Job として `args` で `train ...` を渡す。
# - 依存は ml/requirements.txt（推移的依存まで固定。numpy 2.5.3・scikit-learn 1.9.1 など）だけを --no-deps・wheel のみでインストールする（ソースビルドしない）。
#   ビルド用のステージ（ml-train-deps）と実行ステージを分け、実行イメージに pip のキャッシュ・コンパイラ・ソース以外のものを入れない。
# - 特徴量は再計算しない（Kotlin の export-dataset が作る）。データセットの契約検証は tools/ml の canonical.py・dataset_contract.py（署名のテスト用シードを含む signing.py や
#   合成成果物の生成器、テストは入れない）。DB は `mltrain`（ml_model の SELECT/INSERT のみ）。実データ・学習済みモデル・秘密鍵はイメージに入れない（MODEL_SIGNING_KEY は実行時の Secret）。
# - ENTRYPOINT は exec 形式の python（PID 1）。SIGTERM は ml_train が受け、確定（レジストリ登録）の前なら成果物を片付けて 75 で終わる。終了コードは CONTRACTS §9.1。
# - BLAS のスレッド数は 1 に固定（再現性）。ログは標準出力の JSON 1 行。
FROM python:3.13.17-slim-bookworm@sha256:e32be0e55814a08977501ca863271d9bd02443710db8f2afaf8372e2c4508530 AS ml-train-deps
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
COPY ml/requirements.txt /tmp/requirements.txt
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-deps --only-binary=:all: -r /tmp/requirements.txt

FROM python:3.13.17-slim-bookworm@sha256:e32be0e55814a08977501ca863271d9bd02443710db8f2afaf8372e2c4508530 AS ml-train
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app
ENV TZ=Asia/Tokyo PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/app/src:/app \
    OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
COPY --from=ml-train-deps --chmod=a=rX /opt/venv /opt/venv
WORKDIR /app
COPY --chmod=a=rX ml/src/ml_train /app/src/ml_train
COPY --chmod=a=rX tools/__init__.py /app/tools/__init__.py
COPY --chmod=a=rX tools/ml/__init__.py tools/ml/canonical.py tools/ml/dataset_contract.py /app/tools/ml/
USER 10001:10001
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="jra-bot ml-train" \
      org.opencontainers.image.description="JRA Bot ML training Job (Python). Registers CANDIDATE/REJECTED models only; never promotes to ACTIVE" \
      org.opencontainers.image.source="https://github.com/puttun777/jra-bot-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${GIT_SHA}"
ENTRYPOINT ["/opt/venv/bin/python", "-m", "ml_train"]
