# =========================================================================
# Stage 1: Fast Cached Dependency Compiler
# =========================================================================
FROM maven:3.8.8-eclipse-temurin-17-alpine AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# =========================================================================
# Stage 2: High-Performance Runtime Container
# =========================================================================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install native multimedia binaries required for JavaCV/FFmpeg frame slicing
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libopencv-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Map native engine shared library locations into standard search paths
ENV LD_LIBRARY_PATH="/usr/lib/x86_64-linux-gnu:/usr/local/lib"
ENV OPENCV_VIDEOIO_PRIORITY_API="FFMPEG"
ENV SPRING_PROFILES_ACTIVE="prod"

COPY --from=builder /build/target/*.jar app.jar

# Pre-compile directory anchors for runtime execution footprints
RUN mkdir -p /app/models

EXPOSE 8088

# Boot application injecting high-performance G1GC garbage collection parameters
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=80.0", "-jar", "app.jar"]
