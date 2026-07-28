FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN gradle build --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/gatekeeperd-*.jar ./gatekeeperd.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "gatekeeperd.jar"]