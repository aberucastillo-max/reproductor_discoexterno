#!/usr/bin/env bash
# Compila el APK sin Android Studio. Requiere (Ubuntu/Debian):
#   apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 openjdk-17-jdk
# y el jar de la API de Android 13 de Maven Central (se descarga solo).
set -euo pipefail
cd "$(dirname "$0")"
B=build
API23=/usr/lib/android-sdk/platforms/android-23/android.jar
[ -f "$API23" ] || API23=$(ls /usr/lib/android-sdk/platforms/*/android.jar | head -1)
ANDROID_ALL=${ANDROID_ALL:-$B/android-all-13.jar}
mkdir -p "$B"
if [ ! -f "$ANDROID_ALL" ]; then
  curl -fL -o "$ANDROID_ALL" https://repo1.maven.org/maven2/org/robolectric/android-all/13-robolectric-9030017/android-all-13-robolectric-9030017.jar
fi
rm -rf "$B/gen" "$B/classes" "$B"/*.apk "$B/classes.dex"
mkdir -p "$B/gen" "$B/classes"

aapt package -f -M AndroidManifest.xml -S res -I "$API23" -J "$B/gen" -F "$B/app.unaligned.apk"
javac -nowarn --release 8 -encoding UTF-8 \
      -classpath "$ANDROID_ALL" -d "$B/classes" \
      $(find src "$B/gen" -name '*.java')
dalvik-exchange --dex --min-sdk-version=26 --output="$B/classes.dex" "$B/classes"
(cd "$B" && aapt add app.unaligned.apk classes.dex >/dev/null)
zipalign -f 4 "$B/app.unaligned.apk" "$B/app.aligned.apk"

KS=${KEYSTORE:-$HOME/.android/usb-ntfs-explorer.keystore}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias app \
          -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Explorador USB NTFS" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
               --out ExploradorUSB-NTFS.apk "$B/app.aligned.apk"
apksigner verify ExploradorUSB-NTFS.apk
echo "APK listo: $(pwd)/ExploradorUSB-NTFS.apk"
