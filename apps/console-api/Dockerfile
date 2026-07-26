# 控制面 —— Java 21 + Spring Boot
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# 先只拷 pom 拉依赖:改代码不改依赖时这一层能命中缓存
COPY apps/console-api/pom.xml apps/console-api/pom.xml
RUN cd apps/console-api && mvn -B -q dependency:go-offline || true
COPY packages/domain-schema packages/domain-schema
COPY apps/console-api apps/console-api
RUN cd apps/console-api && mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre-jammy
RUN groupadd -r nebula && useradd -r -g nebula nebula
COPY --from=build /src/apps/console-api/target/nebula-console-api-*.jar /app/console-api.jar
USER nebula
EXPOSE 8080
# 容器内存上限交给 JVM 自己按 cgroup 感知,不写死 -Xmx
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/console-api.jar"]
