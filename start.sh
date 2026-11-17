#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
if ! command -v python >/dev/null 2>&1; then
  echo "Python در Termux پیدا نشد. ابتدا Python را نصب کنید."
  exit 1
fi
mkdir -p data uploads games
python app.py
