# Build stage — JDK 21 with Maven
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Copy both module POMs first — Maven reactor needs them for dependency resolution
COPY pom.xml ./pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml

# Resolve dependencies before copying source (maximises layer cache reuse)
RUN mvn -pl catalog-service -am dependency:resolve --no-transfer-progress -q

# Copy source and build
COPY catalog-service/src catalog-service/src
RUN mvn -pl catalog-service package -DskipTests --no-transfer-progress -q

# Runtime stage — JRE 21 Alpine (minimal footprint)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache wget && \
    addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /workspace/catalog-service/target/*.jar app.jar

USER appuser
EXPOSE 8081

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
