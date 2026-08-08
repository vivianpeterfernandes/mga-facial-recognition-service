# =========================================================================
# Stage 1: Fast Cached Dependency Compiler
# =========================================================================
FROM maven:3.8.8-eclipse-temurin-17-alpine AS BUILDER
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

# Install native multimedia binaries and system graphics extensions
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

# Map library paths explicitly to target system distributions
ENV LD_LIBRARY_PATH="/usr/lib/x86_64-linux-gnu:/usr/local/lib"
ENV OPENCV_VIDEOIO_PRIORITY_API="FFMPEG"
ENV SPRING_PROFILES_ACTIVE="k8s-prod"

ENV DJL_CACHE_DIR="/tmp/djl_cache"
ENV OFFLINE="true"

COPY --from=BUILDER /build/target/*.jar app.jar

# FIX SYNTAX: Separate, clean RUN operations ensure absolute path security
RUN mkdir -p /app/models

RUN curl -L -o /app/models/deploy.prototxt "https://githubusercontent.com"

RUN curl -L -o /app/models/res10_300x300_ssd_iter_140000.caffemodel "https://githubusercontent.com"

RUN curl -L -o /app/models/facenet.pt "https://githubusercontent.com"

EXPOSE 8088

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=80.0", "-jar", "app.jar"]
