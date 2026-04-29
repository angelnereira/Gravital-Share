FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG NDK_VERSION=27.2.12479018
ARG RUST_VERSION=1.84.0
ARG ANDROID_SDK_VERSION=35
ARG JDK_VERSION=21

# System dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential pkg-config libssl-dev clang lld cmake ninja-build \
    openjdk-${JDK_VERSION}-jdk unzip wget curl git ca-certificates \
    python3 python3-pip jq && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-${JDK_VERSION}-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# Android SDK command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
        -O /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools && \
    mv /tmp/cmdtools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm -f /tmp/cmdtools.zip

# Accept licenses and install SDK components
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager \
        "platform-tools" \
        "platforms;android-${ANDROID_SDK_VERSION}" \
        "build-tools;35.0.0" \
        "ndk;${NDK_VERSION}"

ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${NDK_VERSION}
ENV NDK_HOME=${ANDROID_NDK_HOME}

# Rust toolchain
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | \
    sh -s -- -y --default-toolchain ${RUST_VERSION}

ENV PATH="/root/.cargo/bin:${PATH}"

RUN rustup component add rustfmt clippy rust-src && \
    rustup target add \
        aarch64-linux-android \
        armv7-linux-androideabi \
        x86_64-linux-android \
        i686-linux-android \
        x86_64-unknown-linux-gnu

# Cargo tools
RUN cargo install \
    cargo-ndk@3.5.4 \
    cargo-deny@0.16.4 \
    cargo-audit@0.21.2 \
    cargo-fuzz@0.12.0 \
    --locked

WORKDIR /workspaces/Gravital-Share
