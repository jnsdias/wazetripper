# Single toolchain image for the whole Waze -> Kawasaki patch pipeline.
# Everything the scripts need runs inside this image; the host only needs Docker.
#
#   apktool  d8  zipalign  apksigner  aapt2  apkeep  javac  python3  zip
#
# All versions are pinned for reproducible builds.
FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        wget unzip zip python3 git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# --- Android SDK (cmdline-tools -> build-tools + platform) ---
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/build-tools/34.0.0

ARG CMDLINE_TOOLS=commandlinetools-linux-11076708_latest.zip
ARG ANDROID_PLATFORM=android-34
ARG ANDROID_BUILD_TOOLS=34.0.0
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/${CMDLINE_TOOLS} -O /tmp/cmdtools.zip \
    && unzip -q /tmp/cmdtools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
    && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
    && rm /tmp/cmdtools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" > /dev/null

# --- Apktool (pinned jar + wrapper) ---
ARG APKTOOL_VERSION=2.10.0
RUN wget -q https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VERSION}/apktool_${APKTOOL_VERSION}.jar \
        -O /opt/apktool.jar \
    && printf '#!/bin/sh\nexec java -jar /opt/apktool.jar "$@"\n' > /usr/local/bin/apktool \
    && chmod +x /usr/local/bin/apktool

# --- apkeep (pinned prebuilt EFF binary) ---
ARG APKEEP_VERSION=1.0.0
RUN wget -q https://github.com/EFForg/apkeep/releases/download/${APKEEP_VERSION}/apkeep-x86_64-unknown-linux-gnu \
        -O /usr/local/bin/apkeep \
    && chmod +x /usr/local/bin/apkeep

# --- APKEditor (pinned jar): binary split-APK merger (ARSCLib) ---
# Merges base + config splits into ONE standalone apk by merging the binary resource tables and
# copying every res/* entry VERBATIM. It never re-encodes resource XML through aapt2, so it does
# NOT trigger the drawable corruption the golden rule guards against (see DEVELOPMENT.md §11).
ARG APKEDITOR_VERSION=1.4.9
ARG APKEDITOR_SHA256=a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67
RUN wget -q https://github.com/REAndroid/APKEditor/releases/download/V${APKEDITOR_VERSION}/APKEditor-${APKEDITOR_VERSION}.jar \
        -O /opt/apkeditor.jar \
    && echo "${APKEDITOR_SHA256}  /opt/apkeditor.jar" | sha256sum -c - \
    && printf '#!/bin/sh\nexec java -jar /opt/apkeditor.jar "$@"\n' > /usr/local/bin/apkeditor \
    && chmod +x /usr/local/bin/apkeditor

WORKDIR /work
