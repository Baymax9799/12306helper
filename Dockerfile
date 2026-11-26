# 构建层：只用来产出 jar
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# 先只拷 pom，让依赖层可缓存
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行层：只要 JRE
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai \
    DATA_DIR=/data \
    JAVA_OPTS="-Xms128m -Xmx384m" \
    PORT=8080

RUN useradd --uid 1000 --create-home --home-dir /home/app app \
 && mkdir -p /data \
 && chown -R 1000:1000 /data /home/app

WORKDIR /app
COPY --from=build /src/target/tickethelper-linux.jar /app/app.jar

USER 1000
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/healthz" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
