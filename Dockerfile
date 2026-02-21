# Build stage
FROM gradle:9-jdk21 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY gradlew ./
RUN ./gradlew dependencies --no-daemon || true
COPY src ./src
RUN ./gradlew clean build -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre
RUN groupadd -r appuser && useradd -r -g appuser -d /app appuser
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN mkdir -p /app/uploads && chown -R appuser:appuser /app
USER appuser
VOLUME /app/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
