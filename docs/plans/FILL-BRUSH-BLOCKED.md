# FILL-BRUSH native build

Date: 2026-10-24

The first attempt stopped because `KRITA_SRC_DIR` was not at the CMake default `/root/Projects/krita-source`. The 2026-10-23 toolchain is elsewhere. Rebuilt in a fresh directory, not via `scripts/build_native.sh`:

- `KRITA_SRC_DIR=/root/krita-clip`
- `KRITA_BIN_DIR=/root/krita-bin-clip`
- `QT_ANDROID_DIR=/root/Qt6/6.6.3/android_arm64_v8a`
- `DEPS_DIR=/root/ReveriePaint-android/deps`
- NDK `/opt/android-sdk/ndk/25.2.9519653`, `android-23`, `arm64-v8a`, Release

`/root/krita-clip` has `Experiment/displacementEnabled` and `Experiment/speedEnabled`, matching the hardcoded keys. `libreverie_jni.so` linked. Stripped copy is `third_party/android-native-libs/libreverie_jni.so` only. armeabi-v7a was not refreshed. Device checks are still open.
