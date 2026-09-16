#!/bin/bash
# Скачивает артефакты Windows-сборки из Actions и кладёт их в /var/www/reed-download/,
# откуда nginx отдаёт их как https://reedapp.ru/download/<файл>.
# Использование: ./publish-windows-build.sh <run_id>
set -e
RUN_ID="$1"
[ -z "$RUN_ID" ] && { echo "нужен run_id"; exit 1; }
cd /root/reed-app
TOK=$(git remote get-url origin | sed -n 's#.*:\(ghp_[^@]*\)@.*#\1#p')
REPO="reedvpn/reed-app"
TMP=$(mktemp -d)
curl -s -H "Authorization: Bearer $TOK" \
  "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts" > "$TMP/list.json"
python3 - "$TMP" <<'PY' > "$TMP/urls.txt"
import json,sys
t=sys.argv[1]
for a in json.load(open(t+'/list.json'))['artifacts']:
    print(a['name'], a['archive_download_url'])
PY
cat "$TMP/urls.txt"
while read -r NAME URL; do
  [ -z "$URL" ] && continue
  curl -sL -H "Authorization: Bearer $TOK" "$URL" -o "$TMP/$NAME.zip"
  unzip -o -q "$TMP/$NAME.zip" -d "$TMP/$NAME"
done < "$TMP/urls.txt"
find "$TMP" -name "*.exe" -o -name "*.zip" | grep -v "reed-windows-.*\.zip$" || true
STAMP=$(date +%Y%m%d)
for f in $(find "$TMP" -name "*.exe"); do
  cp "$f" "/var/www/reed-download/Reed-VPN-setup-$STAMP.exe"
  echo "выложено: https://reedapp.ru/download/Reed-VPN-setup-$STAMP.exe"
done
for f in $(find "$TMP" -path "*reed-windows-portable*" -name "*.zip"); do
  cp "$f" "/var/www/reed-download/Reed-VPN-portable-$STAMP.zip"
  echo "выложено: https://reedapp.ru/download/Reed-VPN-portable-$STAMP.zip"
done
rm -rf "$TMP"
