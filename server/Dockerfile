FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY server/pom.xml pom.xml
COPY server/src src
RUN mvn -B -ntp package

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /build/target/distilly-platform-0.1.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
