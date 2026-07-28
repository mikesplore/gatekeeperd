FROM gradle:8.11-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN gradle buildFatJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/gatekeeperd-all.jar ./gatekeeperd.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "gatekeeperd.jar", "-port=8080"]
