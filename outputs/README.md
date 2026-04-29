# Gravital Share — Build Outputs

Every successful build drops a ready-to-install APK here.

## File naming

```
GravitalShare-<versionName>-<versionCode>-<build_type>-<abi>-<yyyymmdd_HHMM>.apk
```

Example:
```
GravitalShare-0.1.0-1-debug-arm64-v8a-20260427_1530.apk
```

## Install on device / emulator

```bash
adb install -r outputs/<file>.apk
```

## Build yourself

```bash
./scripts/build-android.sh           # debug, all ABIs
./scripts/build-android.sh --release # release (needs keystore)
./scripts/build-android.sh --abi arm64-v8a  # single ABI (faster)
```
