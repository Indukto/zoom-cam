# Fix Keystore Missing Error

The project fails to build because it's looking for a `debug.keystore` file in the root directory, which is missing. This is caused by a custom `signingConfig` named `debugConfig` in the `app/build.gradle.kts` file.

## Proposed Changes

### [app](file:///F:/#an/Bhig/app)

#### [MODIFY] [build.gradle.kts](file:///F:/#an/Bhig/app/build.gradle.kts)
- Remove the `debugConfig` creation block from `signingConfigs`.
- Remove the line `signingConfig = signingConfigs.getByName("debugConfig")` from the `debug` build type.
- This will allow Gradle to fall back to the default debug keystore located in the user's home directory (`~/.android/debug.keystore`), which is the standard behavior for Android development.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify that the build succeeds without the missing keystore error.

### Manual Verification
- None required as this is a build configuration fix.
