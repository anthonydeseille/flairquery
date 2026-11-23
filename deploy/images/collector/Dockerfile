# FlairQuery collector: reads the observed PostgreSQL (read-only role), stores into fq-store.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S fq && adduser -S fq -G fq
WORKDIR /app
COPY flairquery-collector.jar collector.jar
USER fq
EXPOSE 9465
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/collector.jar"]
