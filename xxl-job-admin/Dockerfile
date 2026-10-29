FROM eclipse-temurin:17-jre-jammy
ENV TZ=Asia/Shanghai LOG_HOME=/data/applogs JAVA_OPTS="" PARAMS=""
WORKDIR /app
RUN groupadd --gid 10001 xxljob && useradd --uid 10001 --gid xxljob --no-create-home xxljob \
    && mkdir -p /data/applogs && chown -R xxljob:xxljob /app /data/applogs
COPY --chown=xxljob:xxljob target/xxl-job-admin.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -DLOG_HOME=$LOG_HOME -jar /app/app.jar $PARAMS \"$@\"", "--"]
