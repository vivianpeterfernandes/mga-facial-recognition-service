# =========================================================================
# Stage 1: Fast Cached Dependency Compiler (FORCED ALIAS TO UPPERCASE)
# =========================================================================
FROM maven:3.8.8-eclipse-temurin-17-alpine AS BUILDER
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

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libopencv-dev \
    curl \
    libgomp1 \
    libgl1-mesa-glx \
    libgl1 \
    libx11-6 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

ENV LD_LIBRARY_PATH="/usr/lib/x86_64-linux-gnu:/usr/local/lib"
ENV OPENCV_VIDEOIO_PRIORITY_API="FFMPEG"
ENV SPRING_PROFILES_ACTIVE="k8s-prod"

ENV DJL_CACHE_DIR="/tmp/djl_cache"
ENV OFFLINE="true"

# FIX: Point strictly to the explicit UPPERCASE multi-stage builder alias
COPY --from=BUILDER /build/target/*.jar app.jar

RUN mkdir -p /app/models

EXPOSE 8088

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=80.0", "-jar", "app.jar"]
