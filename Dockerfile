# 在构建主机架构编译 Java，运行层根据目标平台选择 amd64 或 arm64。
FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY xxl-job-core ./xxl-job-core
COPY xxl-job-admin ./xxl-job-admin
COPY xxl-job-executor-samples ./xxl-job-executor-samples
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl xxl-job-admin -am -DskipTests -Dmaven.javadoc.skip=true -Dgpg.skip=true package

FROM eclipse-temurin:17-jre-jammy
ENV TZ=Asia/Shanghai LOG_HOME=/data/applogs JAVA_OPTS="" PARAMS=""
WORKDIR /app
RUN groupadd --gid 10001 xxljob && useradd --uid 10001 --gid xxljob --no-create-home xxljob \
    && mkdir -p /data/applogs && chown -R xxljob:xxljob /app /data/applogs
COPY --from=build --chown=xxljob:xxljob /src/xxl-job-admin/target/xxl-job-admin.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
# exec 保证 Java 收到容器停止信号；JVM 参数必须放在 -jar 之前。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -DLOG_HOME=$LOG_HOME -jar /app/app.jar $PARAMS \"$@\"", "--"]
