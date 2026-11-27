# 构建层：只用来产出 jar
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# 可选：国内网络下 Maven 中央仓库很慢，传 --build-arg MAVEN_MIRROR=<镜像地址> 加速。
# 例：https://maven.aliyun.com/repository/public
ARG MAVEN_MIRROR=
RUN if [ -n "$MAVEN_MIRROR" ]; then \
      mkdir -p /root/.m2 && \
      printf '%s' '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>'"$MAVEN_MIRROR"'</url></mirror></mirrors></settings>' \
        > /root/.m2/settings.xml; \
    fi

# 先只拷 pom，让依赖层可缓存
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行层：只要 JRE
FROM eclipse-temurin:17-jre-jammy

# 时区与中文 locale 都写进文件系统，不依赖运行时再配。
# 注意：zh_CN.UTF-8 必须先 locale-gen 出来，否则 glibc 会退回 C/POSIX 并打 setlocale 警告。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata locales \
 && sed -i 's/^# *\(zh_CN.UTF-8\)/\1/' /etc/locale.gen \
 && sed -i 's/^# *\(en_US.UTF-8\)/\1/' /etc/locale.gen \
 && locale-gen \
 && update-locale LANG=zh_CN.UTF-8 \
 && ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
 && echo "Asia/Shanghai" > /etc/timezone \
 && rm -rf /var/lib/apt/lists/*

# 覆盖基础镜像自带的 en_US 默认值（基镜像本就有这三个变量，不算新增配置项）
ENV LANG=zh_CN.UTF-8 \
    LANGUAGE=zh_CN:zh \
    LC_ALL=zh_CN.UTF-8

RUN useradd --uid 1000 --create-home --home-dir /home/app app \
 && mkdir -p /data \
 && chown -R 1000:1000 /data /home/app

WORKDIR /app
COPY --from=build /src/target/12306helper.jar /app/app.jar

# 声明数据卷，图形界面会据此提示挂载；不写任何 ENV，避免创建容器时被预填一堆英文默认值。
# 端口默认 28080、数据目录默认 /data，都由程序内部兜底。
VOLUME ["/data"]

USER 1000
EXPOSE 28080

HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT:-28080}/healthz" || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:--Xms128m -Xmx384m} -jar /app/app.jar"]
