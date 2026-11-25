#!/usr/bin/env bash
# Compila el APK sin Android Studio. Requiere (Ubuntu/Debian):
#   apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 openjdk-17-jdk unzip
# Descarga de Maven Central: API de Android 13 (robolectric android-all), libVLC 3.7.8 y ASM.
set -euo pipefail
cd "$(dirname "$0")"
B=build
M=https://repo1.maven.org/maven2
API23=/usr/lib/android-sdk/platforms/android-23/android.jar
[ -f "$API23" ] || API23=$(ls /usr/lib/android-sdk/platforms/*/android.jar | head -1)
VLC_VER=3.7.8
mkdir -p "$B/dl"
get() { [ -f "$B/dl/$2" ] || curl -fL -o "$B/dl/$2" "$1"; }
get $M/org/robolectric/android-all/13-robolectric-9030017/android-all-13-robolectric-9030017.jar android-all-13.jar
get $M/org/videolan/android/libvlc-all/$VLC_VER/libvlc-all-$VLC_VER.aar libvlc.aar
get $M/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar asm.jar
get $M/org/ow2/asm/asm-tree/9.7.1/asm-tree-9.7.1.jar asm-tree.jar
ANDROID_ALL=$B/dl/android-all-13.jar

# libVLC: recursos, código (convertido a Java 8 para dx) y librerías nativas arm64 (Galaxy Tab S6 Lite)
rm -rf "$B/vlc" && mkdir -p "$B/vlc"
unzip -q "$B/dl/libvlc.aar" -d "$B/vlc/aar"
mkdir -p "$B/tools"
javac -nowarn -cp "$B/dl/asm.jar:$B/dl/asm-tree.jar" -d "$B/tools" tools/Desugar11.java
java -cp "$B/dl/asm.jar:$B/dl/asm-tree.jar:$B/tools" Desugar11 "$B/vlc/aar/classes.jar" "$B/vlc/libvlc-java8.jar"

rm -rf "$B/gen" "$B/classes" "$B/apk" "$B"/*.apk "$B/classes.dex"
mkdir -p "$B/gen" "$B/classes" "$B/apk/lib/arm64-v8a"
cp "$B"/vlc/aar/jni/arm64-v8a/*.so "$B/apk/lib/arm64-v8a/"

aapt package -f --auto-add-overlay --extra-packages org.videolan \
     -M AndroidManifest.xml -S res -S "$B/vlc/aar/res" -I "$API23" -J "$B/gen" -F "$B/app.unaligned.apk"
javac -nowarn --release 8 -encoding UTF-8 \
      -classpath "$ANDROID_ALL:$B/vlc/libvlc-java8.jar" -d "$B/classes" \
      $(find src "$B/gen" -name '*.java')
dalvik-exchange --dex --min-sdk-version=26 --output="$B/apk/classes.dex" "$B/classes" "$B/vlc/libvlc-java8.jar"
(cd "$B/apk" && aapt add ../app.unaligned.apk classes.dex lib/arm64-v8a/*.so >/dev/null)
zipalign -f -p 4 "$B/app.unaligned.apk" "$B/app.aligned.apk"

KS=${KEYSTORE:-$HOME/.android/usb-ntfs-explorer.keystore}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias app \
          -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Explorador USB NTFS" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
               --out ExploradorUSB-NTFS.apk "$B/app.aligned.apk"
rm -f ExploradorUSB-NTFS.apk.idsig
apksigner verify ExploradorUSB-NTFS.apk
echo "APK listo: $(pwd)/ExploradorUSB-NTFS.apk ($(du -h ExploradorUSB-NTFS.apk | cut -f1))"
