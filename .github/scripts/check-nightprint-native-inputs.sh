#!/usr/bin/env bash
set -euo pipefail

test "$(git rev-parse HEAD:app/src/main/cpp)" = 71e6cfe52e179fa5a15d539417766c798352fcf9
test "$(git rev-parse HEAD:app/src/main/jniLibs)" = f0140c41d25e218f9ad63e2e90eb6a55d37571c5
test "$(git rev-parse HEAD:app/src/main/java/com/u1/slicer/NativeLibrary.kt)" = abf46e51a7be1a44d3374a613dcfc184f77ffee6
test "$(git rev-parse HEAD:scripts)" = e9a7f1c85d350dd12a8ce48c6e5e5e348a2c7a94
test "$(git rev-parse HEAD:app/src/main/java/com/u1/slicer/data/ModelInfo.kt)" = ab2dfd2d320f09c100dee3276295dc2a5c8bce9d
test "$(git rev-parse HEAD:app/src/main/java/com/u1/slicer/data/SliceConfig.kt)" = 52b66b9b93a9b22d9176f6951ecfd58fb53c707a
test "$(git rev-parse HEAD:app/src/main/java/com/u1/slicer/data/SliceResult.kt)" = 3db70e296ced9cdab18b7780ba0901598aa7070c
test "$(git rev-parse HEAD:app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt)" = 1ede139f204cc4020f14aaad8168396cadaa2add
