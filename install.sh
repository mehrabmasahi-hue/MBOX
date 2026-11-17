#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
echo "======================================"
echo "       M BOX - OFFLINE INSTALLER"
echo "======================================"
if ! command -v python >/dev/null 2>&1; then
  echo "خطا: Python در Termux نصب نیست."
  echo "برای نصب اولیه Python باید یک‌بار بسته Termux در دسترس باشد؛ پس از نصب، اجرای M BOX کاملاً آفلاین است."
  echo "اگر Python از قبل نصب است، این پیام نباید نمایش داده شود."
  exit 1
fi
mkdir -p data uploads games
chmod +x start.sh
python -m py_compile app.py
echo "آماده است. هیچ کتابخانه پایتونی دانلود یا نصب نشد."
echo "برای اجرا: ./start.sh"
