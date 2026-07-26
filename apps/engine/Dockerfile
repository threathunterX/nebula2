# 计算引擎 —— 提交到 Flink 的作业 jar
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /src
COPY apps/engine/pom.xml apps/engine/pom.xml
RUN cd apps/engine && mvn -B -q dependency:go-offline || true
COPY apps/engine apps/engine
COPY tests tests
COPY seeds seeds
RUN cd apps/engine && mvn -B -q -DskipTests package

# 基于官方 Flink 镜像:作业 jar 放进 usrlib,JobManager 以 application 模式启动它。
# 不自己拼 Flink 运行时 —— 版本错配的排查成本远高于多一层基础镜像。
FROM flink:1.20-java17
COPY --from=build /src/apps/engine/target/nebula-engine-*.jar /opt/flink/usrlib/nebula-engine.jar

# checkpoint 目录必须在镜像里就存在并归 flink 所有。命名卷首次挂载时会沿用
# 镜像中该路径的属主;镜像里没有这个目录,Docker 就新建一个 root 的空目录,
# 而 Flink 以 flink 用户运行,建 checkpoint 子目录时权限不足 —— 作业能提交、
# 能进入 RUNNING,然后在第一次 checkpoint 时失败。
RUN mkdir -p /flink-checkpoints && chown flink:flink /flink-checkpoints
