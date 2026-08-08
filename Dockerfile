# =========================================================================
# Stage 2: High-Performance Runtime Stage (With Embedded Native Extensions)
# =========================================================================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# FIX: Removed the non-existent libextstack7 package and added standard Linux dependencies
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

# Tell DJL to look strictly for prepackaged, local jars and skip cloud repository downloads
ENV DJL_CACHE_DIR="/tmp/djl_cache"
ENV OFFLINE="true"

COPY --from=builder /build/target/*.jar app.jar

RUN mkdir -p /app/models

EXPOSE 8088

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=80.0", "-jar", "app.jar"]
