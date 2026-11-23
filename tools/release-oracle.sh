#!/usr/bin/env bash
# Викладає випуск на сервер Oracle, поряд з Inertia: APK як pdfshoot-<версія>.apk і як pdfshoot.apk
# (стале посилання для завантаження), і перевіряє, що він роздається. Версія читається з APK.
#
# Останнім пишеться version.json: за ним встановлені застосунки бачать нову версію й оновлюються самі.
#
#   ./gradlew :app:assembleRelease && tools/release-oracle.sh "Що нового в цій версії"
#
# Потрібні ssh-псевдонім wms-oracle (~/.ssh/config) і aapt2 (Android SDK build-tools).
set -euo pipefail

NOTES=${1:-}
APK=${2:-app/build/outputs/apk/release/app-release.apk}
HOST=wms-oracle
DIR=/opt/inertia-dist/pdfshoot   # тека Inertia, яку вже роздає Caddy: окремий конфіг не потрібен
URL=https://92-5-101-235.sslip.io/pdfshoot/

AAPT2=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/aapt2 | tail -1)
BADGING=$("$AAPT2" dump badging "$APK" | sed -n 1p)
grep -q "name='uk.mwms1488.pdfshoot'" <<<"$BADGING" || { echo "Не релізна збірка PDF Shoot: $APK" >&2; exit 1; }
CODE=$(sed -E "s/.*versionCode='([0-9]+)'.*/\1/" <<<"$BADGING")
NAME=$(sed -E "s/.*versionName='([^']+)'.*/\1/" <<<"$BADGING")
echo "Випуск $NAME ($CODE)"

ssh "$HOST" "mkdir -p $DIR"
scp -q "$APK" "$HOST:$DIR/pdfshoot-$NAME.apk.tmp"
ssh "$HOST" "cd $DIR && mv pdfshoot-$NAME.apk.tmp pdfshoot-$NAME.apk && cp pdfshoot-$NAME.apk pdfshoot.apk.tmp && mv pdfshoot.apk.tmp pdfshoot.apk"

python3 -c 'import json, sys
print(json.dumps({"versionCode": int(sys.argv[1]), "versionName": sys.argv[2], "url": sys.argv[3], "notes": sys.argv[4]},
                 ensure_ascii=False, indent=2))' "$CODE" "$NAME" "${URL}pdfshoot-$NAME.apk" "$NOTES" \
  | ssh "$HOST" "cat > $DIR/version.json.tmp && mv $DIR/version.json.tmp $DIR/version.json"

curl -fsS "${URL}version.json" | grep "\"versionCode\": $CODE" >/dev/null && echo "version.json: $CODE"
[ "$(curl -sI -o /dev/null -w '%{http_code}' "${URL}pdfshoot.apk")" = 200 ] && echo "Роздається: ${URL}pdfshoot.apk"
