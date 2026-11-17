#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

if ! command -v git >/dev/null 2>&1; then
  echo "Git نصب نیست. در Termux اجرا کنید: pkg install git -y"
  exit 1
fi

echo "=============================================="
echo " M BOX | آماده‌سازی و ارسال سورس به GitHub"
echo "=============================================="
echo "ابتدا در GitHub یک مخزن خالی با نام MBOX بسازید."
echo "لینک HTTPS مخزن را مانند نمونه وارد کنید:"
echo "https://github.com/USERNAME/MBOX.git"
echo
read -r -p "آدرس مخزن GitHub: " REPO_URL
if [[ ! "$REPO_URL" =~ ^https://github\.com/[^/]+/[^/]+([.]git)?$ ]]; then
  echo "آدرس مخزن معتبر نیست. لینک HTTPS مخزن GitHub را وارد کنید."
  exit 1
fi

if [ ! -d .git ]; then git init; fi
git config user.name "M BOX Builder"
git config user.email "mbox-builder@users.noreply.github.com"
git add .
if git diff --cached --quiet; then
  echo "تغییری برای commit وجود ندارد."
else
  git commit -m "M BOX initial Android apps and LAN host"
fi
git branch -M main
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REPO_URL"
else
  git remote add origin "$REPO_URL"
fi

echo
echo "اکنون GitHub احراز هویت می‌خواهد."
echo "نام کاربری GitHub را وارد کنید و در قسمت Password، توکن دسترسی (PAT) را وارد کنید؛ رمز عادی حساب کار نمی‌کند."
echo "توکن را داخل فایل‌ها یا پیام‌ها قرار ندهید."
echo
git push -u origin main

echo
echo "ارسال سورس با موفقیت انجام شد."
echo "در مخزن GitHub به بخش Actions بروید؛ گردش‌کار ساخت APK اجرا می‌شود."
echo "فایل‌ها از بخش Actions > اجرای آخر > Artifacts قابل دریافت خواهند بود."
