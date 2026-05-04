#!/bin/bash
echo "Building Soundpeats Controller APK with Gradle Wrapper 8.5..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo "Build Successful! APK is located at: app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "To install on your connected device, make sure USB Debugging is on and run:"
    echo "adb install app/build/outputs/apk/debug/app-debug.apk"
else
    echo "Build Failed."
    echo "Make sure you have Java installed and the Android SDK configured (ANDROID_HOME environment variable)."
fi
