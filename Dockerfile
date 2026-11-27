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

# 时区直接写进文件系统，不依赖运行时的 TZ 环境变量
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
 && echo "Asia/Shanghai" > /etc/timezone \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --uid 1000 --create-home --home-dir /home/app app \
 && mkdir -p /data \
 && chown -R 1000:1000 /data /home/app

WORKDIR /app
COPY --from=build /src/target/tickethelper-linux.jar /app/app.jar

# 声明数据卷，图形界面会据此提示挂载；不写任何 ENV，避免创建容器时被预填一堆英文默认值。
# 端口默认 28080、数据目录默认 /data，都由程序内部兜底。
VOLUME ["/data"]

USER 1000
EXPOSE 28080

HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT:-28080}/healthz" || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:--Xms128m -Xmx384m} -jar /app/app.jar"]
