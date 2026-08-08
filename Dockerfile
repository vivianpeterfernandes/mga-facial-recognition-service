# =========================================================================
# Stage 1: Build Jar Stage (Using multi-threading for speed)
# =========================================================================
FROM maven:3.8.8-eclipse-temurin-17-alpine AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# =========================================================================
# Stage 2: High-Performance Runtime Stage (With Embedded Native Extensions)
# =========================================================================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# FIX: Added essential graphic & shared memory extensions to satisfy native OpenCV linkages
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libopencv-dev \
    curl \
    libgomp1 \
    libgl1-mesa-glx \
    libglib2.0-0 \
    libextstack7 \
    && rm -rf /var/lib/apt/lists/*

# Map library paths explicitly to target system distributions
ENV LD_LIBRARY_PATH="/usr/lib/x86_64-linux-gnu:/usr/local/lib"
ENV OPENCV_VIDEOIO_PRIORITY_API="FFMPEG"
ENV SPRING_PROFILES_ACTIVE="k8s-prod"

# Tell DJL to look strictly for prepackaged, local jars and skip cloud repository downloads
ENV DJL_CACHE_DIR="/tmp/djl_cache"
ENV OFFLINE="true"

COPY --from=builder /build/target/*.jar app.jar

RUN mkdir -p /app/models

EXPOSE 8088

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=80.0", "-jar", "app.jar"]
