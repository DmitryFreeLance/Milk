FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/max-milk-bot-1.0.0.jar /app/bot.jar
RUN mkdir -p /app/data

ENV MAX_DB_PATH=/app/data/milk-bot.sqlite
ENV MILK_BOT_TIMEZONE=Europe/Moscow
ENV MILK_BASE_FAT_PERCENT=3.4
ENV MILK_BASE_PROTEIN_PERCENT=3.0
ENV MAX_LONG_POLL_TIMEOUT_SECONDS=25
ENV MILK_SHIFT_SUMMARY_TIME=20:00
ENV MILK_AUTO_ROTATE_PORTRAIT_IMAGES=true

VOLUME ["/app/data"]

CMD ["java", "-jar", "/app/bot.jar"]
