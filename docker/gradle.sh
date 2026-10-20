#!/bin/sh
# Runs ./gradlew inside the Android builder container (run on the build host from the project root).
# Usage: docker/gradle.sh <gradle args...>
set -e
IMAGE=onvifipcam-android:1
docker image inspect $IMAGE >/dev/null 2>&1 || docker build -t $IMAGE --label owner=claude -f docker/Dockerfile.android docker
docker volume inspect onvifipcam-gradle >/dev/null 2>&1 || docker volume create onvifipcam-gradle >/dev/null
exec docker run --rm --label owner=claude --name onvifipcam-gradle-$$ \
  --user "$(id -u):$(id -g)" -e HOME=/tmp -e GRADLE_USER_HOME=/cache/gradle \
  -v onvifipcam-gradle:/cache/gradle -v "$PWD":/work -w /work \
  $IMAGE ./gradlew --console=plain "$@"
