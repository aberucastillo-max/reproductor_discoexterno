#!/usr/bin/env bash
# Crea discos NTFS de prueba con ntfs-3g y comprueba que el lector de la app
# devuelve exactamente los mismos archivos (nombre, tamaño y SHA-256).
# Requiere: ntfs-3g, fdisk (sfdisk), python3, JDK. Se ejecuta como root (monta FUSE).
set -euo pipefail
cd "$(dirname "$0")/.."
W=${1:-$(mktemp -d)}
mkdir -p "$W/imgs" "$W/classes"
python3 test/make_images.py "$W/imgs"
javac -nowarn -encoding UTF-8 -d "$W/classes" src/com/usbexplorer/ntfs/core/*.java test/*.java
fail=0
for ref in "$W"/imgs/*.ref; do
  n=$(basename "$ref" .ref)
  java -Dstdout.encoding=UTF-8 -cp "$W/classes" TestMain "$W/imgs/$n.img" "$(cat "$W/imgs/$n.bs")" > "$W/imgs/$n.out" 2>/dev/null
  if diff -q "$ref" "$W/imgs/$n.out" >/dev/null; then echo "OK    $n ($(wc -l < "$ref") entradas)"; else echo "FALLO $n"; fail=1; fi
  # índice MFT: mismas rutas y tamaños (los enlaces duros aparecen una sola vez)
  java -Dstdout.encoding=UTF-8 -cp "$W/classes" IndexTest "$W/imgs/$n.img" "$(cat "$W/imgs/$n.bs")" > "$W/imgs/$n.idx" 2>/dev/null
  sed -E 's/^(F .*) [0-9a-f]{64}$/\1/' "$ref" | grep -v '^F /enlaces/' > "$W/imgs/$n.exp"
  if diff -q "$W/imgs/$n.exp" "$W/imgs/$n.idx" >/dev/null; then echo "OK    $n índice"; else echo "FALLO $n índice"; fail=1; fi
done
java -Dstdout.encoding=UTF-8 -cp "$W/classes" SuggestTest "$W/imgs/nombres.img" 2>/dev/null | tee "$W/sug.txt"
grep -q FALLO "$W/sug.txt" && fail=1
exit $fail
