# M BOX — Controller + Display

نسخه پایه پروژه اندرویدی M BOX با دو خروجی مستقل:

- **M BOX Controller** — اپلیکیشن دسته برای گوشی‌ها؛ اتصال با کد چهاررقمی، تأیید ورود توسط مدیر، کنترل افقی و بارگذاری بازی `.123T`.
- **M BOX Display** — اپلیکیشن نمایشگر؛ میزبان سرور LAN داخلی روی تلویزیون، نمایش کد اتاق، مدیریت بازیکنان و اجرای بازی HTML5.
- بازی نمونه **Neon Arena** به‌صورت پیش‌فرض داخل Display قرار دارد تا اتصال و کنترل ۱ تا ۴ بازیکن بدون بارگذاری فایل جداگانه آزمایش شود.

پس از نصب APKها، برای اجرای بازی نیازی به Termux، کامپیوتر یا اینترنت نیست. گوشی‌ها و تلویزیون باید به یک شبکه محلی مشترک (معمولاً یک Wi-Fi) متصل باشند. Termux فقط برای ارسال سورس به GitHub استفاده می‌شود.

## ارسال سورس با Termux

1. در GitHub یک repository خالی با نام دلخواه (مثلاً `MBOX`) بسازید. گزینه‌های README، License و `.gitignore` را هنگام ساخت فعال نکنید تا مخزن خالی بماند.
2. فایل ZIP را در پوشه Downloads گوشی قرار دهید.
3. در Termux، دسترسی فایل و ابزارهای لازم را فعال و ZIP را در پوشه خانه استخراج کنید:

```bash
pkg update -y
pkg install git unzip -y
termux-setup-storage
unzip ~/storage/downloads/MBOX-Android-GitHub-Upload.zip -d ~
cd ~/MBOX
bash upload_to_github.sh
```

اگر ZIP را با نام یا در مسیر دیگری ذخیره کرده‌اید، مسیر همان فایل را در دستور `unzip` جایگزین کنید. اگر قبلاً پوشه `~/MBOX` را استخراج کرده‌اید، فقط از `cd ~/MBOX` به بعد را اجرا کنید.

4. آدرس HTTPS مخزن GitHub را وارد کنید. هنگام درخواست رمز، به‌جای رمز حساب از Personal Access Token دارای دسترسی Contents: Read and write استفاده کنید. توکن را در چت یا فایل پروژه ننویسید.
5. در GitHub بخش **Actions** را باز کنید. گردش‌کار Android APKs هر دو برنامه را می‌سازد.
6. از اجرای موفق workflow، بخش **Artifacts**، فایل `MBOX-Android-APKs` را دریافت کنید. APKهای Debug امضای آزمایشی دارند و قابل نصب‌اند.

## ساخت با Android Studio یا Gradle

پروژه را در Android Studio باز کنید و SDK Platform 37 و Android Build Tools نسخه 36.0.0 یا جدیدتر را نصب کنید. با JDK 17 یا بالاتر، دستورات زیر را اجرا کنید:

```bash
./gradlew assembleControllerDebug assembleDisplayDebug
```

مسیر خروجی‌ها:

- `app/build/outputs/apk/controller/debug/`
- `app/build/outputs/apk/display/debug/`

Release APKهای این workflow فعلاً بدون امضای اختصاصی تولید می‌شوند؛ برای انتشار عمومی باید کلید امضای شخصی و GitHub Secrets تعریف شود. APKهای Debug در حال حاضر برای تست و نصب مستقیم مناسب‌اند.

## شبکه و مجوزها

- Display به‌صورت داخلی روی TCP `1212`، UDP `1213` برای کشف نمایشگر و UDP `1214` برای ورودی بازی گوش می‌دهد.
- Controller با پخش پیام کشف محلی، نمایشگر دارای کد درست را پیدا می‌کند.
- در Android 17 (API 37) مجوز `ACCESS_LOCAL_NETWORK` پیش از استفاده از شبکه محلی به‌صورت زمان اجرا درخواست می‌شود. در نسخه‌های قدیمی‌تر اندروید، مجوز جداگانه شبکه محلی وجود ندارد و ارتباط با مجوز عادی `INTERNET` انجام می‌شود. انتخاب فایل بازی نیز پنجره امن انتخاب‌گر خود اندروید را باز می‌کند.
- برای انتخاب فایل بازی از Android System File Picker استفاده می‌شود؛ مجوز دسترسی گسترده به حافظه درخواست نمی‌شود.
- پورت‌های 1212 تا 1214 باید در شبکه محلی آزاد باشند و قابلیت Client Isolation در روتر نباید ارتباط دستگاه‌ها را مسدود کرده باشد.

## بسته بازی `.123T`

فایل `.123T` یک ZIP با پسوند اختصاصی است و باید یک `index.html` در ریشه یا حداکثر پنج پوشه پایین‌تر داشته باشد. هنگام اجرا، بسته در فضای داخلی Display استخراج و با WebView تمام‌صفحه باز می‌شود. حداکثر اندازه فایل بارگذاری‌شده ۱۰۰ مگابایت است.

### ورودی بازی

Display در طول اجرا، ورودی بازیکنان را در `window.MBOX_INPUT` قرار می‌دهد و رویداد `mboxinput` را ارسال می‌کند. ساختار داده:

```javascript
{
  players: [
    { id: "...", name: "بازیکن", player: 1,
      input: { up: false, down: false, left: false, right: false,
               a: false, b: false, x: false, y: false,
               gas: false, brake: false, nitro: false } }
  ],
  player1: { up: false, gas: true },
  count: 1
}
```

نمونه مصرف:

```javascript
window.addEventListener('mboxinput', (event) => {
  const players = event.detail.players || [];
  const firstPlayer = players.find(p => p.player === 1);
  if (firstPlayer?.input.gas) {
    // اعمال ورودی گاز در بازی
  }
});
```

## وضعیت این نسخه

این مخزن نسخه آغازین قابل توسعه است: دو flavor مستقل APK، رابط فارسی Liquid Glass، آیکون‌های متفاوت، سرور LAN داخلی، کد اتاق، تأیید بازیکنان، انتقال ورودی UDP، بارگذاری و استخراج امن بسته‌های بازی و شمارش معکوس شروع در آن پیاده‌سازی شده‌اند.

**محدودیت تست:** در این محیط Android SDK/شبیه‌ساز و تلویزیون واقعی در دسترس نبود؛ بنابراین build نهایی APK و آزمون واقعی چند دستگاه در این مرحله اجرا نشده است. اولین build در GitHub Actions انجام می‌شود. پس از آن باید اتصال روی Wi-Fi واقعی، مجوز Android 17، انتخاب فایل `.123T` و کنترل چهار بازیکن آزمایش شود. هر خطای build یا اجرا باید با log همان اجرا رفع شود؛ این نسخه را هنوز نباید نسخه نهاییِ تضمین‌شده و بدون خطا دانست.
