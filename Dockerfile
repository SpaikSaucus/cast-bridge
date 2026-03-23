# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk

# Prevent interactive prompts
ENV DEBIAN_FRONTEND=noninteractive

# Install required system packages
RUN apt-get update && apt-get install -y --no-install-recommends \
    unzip \
    wget \
    && rm -rf /var/lib/apt/lists/*

# Set up Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# Download and install Android command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools && \
    mv /tmp/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept licenses and install SDK components
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# Install Gradle
ENV GRADLE_VERSION=8.5
ENV GRADLE_HOME=/opt/gradle
ENV PATH="${GRADLE_HOME}/bin:${PATH}"

RUN wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -O /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    mv /opt/gradle-${GRADLE_VERSION} ${GRADLE_HOME} && \
    rm /tmp/gradle.zip

# Copy project source
WORKDIR /project
COPY . .

# Generate keystore and build APK using secrets (not persisted in image layers)
RUN --mount=type=secret,id=KEYSTORE_PASSWORD \
    --mount=type=secret,id=KEY_ALIAS \
    --mount=type=secret,id=KEY_PASSWORD \
    export KEYSTORE_PASSWORD=$(cat /run/secrets/KEYSTORE_PASSWORD) && \
    export KEY_ALIAS=$(cat /run/secrets/KEY_ALIAS) && \
    export KEY_PASSWORD=$(cat /run/secrets/KEY_PASSWORD) && \
    keytool -genkeypair -v -keystore castbridge-release.keystore \
        -alias ${KEY_ALIAS} -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass ${KEYSTORE_PASSWORD} -keypass ${KEY_PASSWORD} \
        -dname "CN=CastBridge, O=CastBridge, L=Unknown, ST=Unknown, C=US" \
        2>/dev/null && \
    gradle assembleRelease --no-daemon

# Copy APK to output on run
CMD ["/bin/sh", "-c", "cp /project/app/build/outputs/apk/release/app-release.apk /output/CastBridge.apk && echo 'APK copied to /output/CastBridge.apk'"]
