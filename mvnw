#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -n "${FAKEPLAYER_MAVEN_HOME:-}" ] && [ -x "$FAKEPLAYER_MAVEN_HOME/bin/mvn" ]; then
    exec "$FAKEPLAYER_MAVEN_HOME/bin/mvn" -f "$BASE_DIR/pom.xml" "$@"
fi

if command -v mvn >/dev/null 2>&1; then
    exec mvn "$@"
fi

MAVEN_VERSION=3.9.11
CACHE_ROOT=${XDG_CACHE_HOME:-${TMPDIR:-/tmp}}/fakeplayer-maven
MAVEN_HOME="$CACHE_ROOT/apache-maven-$MAVEN_VERSION"
MAVEN_ARCHIVE="$CACHE_ROOT/apache-maven-$MAVEN_VERSION-bin.tar.gz"
MAVEN_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.tar.gz"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
    mkdir -p "$CACHE_ROOT"
    if [ ! -f "$MAVEN_ARCHIVE" ]; then
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL --retry 5 --retry-all-errors --connect-timeout 10 --max-time 180 \
                -o "$MAVEN_ARCHIVE.part" "$MAVEN_URL"
        elif command -v wget >/dev/null 2>&1; then
            wget -q --timeout=10 --tries=5 -O "$MAVEN_ARCHIVE.part" "$MAVEN_URL"
        else
            echo "mvnw requires curl or wget to bootstrap Maven" >&2
            exit 1
        fi
        tar -tzf "$MAVEN_ARCHIVE.part" >/dev/null
        mv "$MAVEN_ARCHIVE.part" "$MAVEN_ARCHIVE"
    fi
    tar -xzf "$MAVEN_ARCHIVE" -C "$CACHE_ROOT"
fi

exec "$MAVEN_HOME/bin/mvn" -f "$BASE_DIR/pom.xml" "$@"
