# LAN Share

مشاركة ملفات الكمبيوتر مع الهاتف عبر **شبكة LAN في المحل** (نفس الراوتر / Wi‑Fi).  
تصفح المجلدات، تشغيل الأغاني والفيديو، ونسخ الملفات إلى الهاتف.

## تحميل التطبيقات الجاهزة

| الجهاز | الملف | رابط مباشر (يعمل في اليمن بدون VPN) | رابط احتياطي |
|---|---|---|---|
| أندرويد | `LAN-Share.apk` | [⚡ تحميل مباشر (سيرفر سريع)](https://gh-proxy.com/https://github.com/iggdigd218-dev/LAN-Share/releases/download/v1.0.0/LAN-Share.apk) | [تحميل بديل](https://ghproxy.net/https://github.com/iggdigd218-dev/LAN-Share/releases/download/v1.0.0/LAN-Share.apk) |
| ويندوز | `LAN-Share.exe` | [⚡ تحميل مباشر (سيرفر سريع)](https://gh-proxy.com/https://github.com/iggdigd218-dev/LAN-Share/releases/download/v1.0.0/LAN-Share.exe) | [تحميل بديل](https://ghproxy.net/https://github.com/iggdigd218-dev/LAN-Share/releases/download/v1.0.0/LAN-Share.exe) |

> **ملاحظة**: خوادم GitHub الأصلية محجوبة في اليمن (`release-assets.githubusercontent.com`). الروابط المباشرة أعلاه تعمل مباشرة عبر شبكة CDN السريعة بدون أي حجب.

الإصدارات: [Releases](https://github.com/iggdigd218-dev/LAN-Share/releases)

---

## طريقة الاستخدام

1. على **الكمبيوتر**: شغّل `LAN-Share.exe` واترك النافذة مفتوحة.  
   سيظهر العنوان مثل `http://192.168.1.15:8080` ورمز **PIN**.
2. إن ظهر تحذير SmartScreen: **المزيد من المعلومات** ثم **تشغيل على أي حال**.
3. إن سأل الجدار الناري: اسمح بالوصول للشبكات الخاصة.
4. على **الهاتف**: ثبّت APK (فعّل مصادر غير معروفة إن لزم).
5. تأكد أن الهاتف والكمبيوتر على **نفس شبكة المحل**.
6. في التطبيق: **بحث تلقائي** أو أدخل العنوان يدوياً ثم PIN.
7. تصفح الملفات:
   - مجلد → فتح
   - أغنية / فيديو → تشغيل على الهاتف
   - ضغط مطوّل أو زر النسخ → تنزيل إلى الهاتف

---

## التشغيل من الشيفرة (اختياري)

### ويندوز

```bat
python -m pip install fastapi uvicorn python-multipart qrcode[pil] aiofiles pillow
python windows\app.py
```

أو `windows\تشغيل.bat`

### أندرويد

افتح مجلد `android/` في Android Studio ثم Run.

بناء EXE تلقائي عبر GitHub Actions: `.github/workflows/build-windows.yml`

---

## ملاحظات

- لا يحتاج إنترنت خارجي أثناء الاستخدام.
- إن فشل البحث التلقائي، اكتب عنوان IP الظاهر على شاشة الكمبيوتر.
- رمز PIN يتغير في كل تشغيل.

## المجلدات

| المسار | الوظيفة |
|---|---|
| `server.py` | خادم المشاركة |
| `windows/app.py` | واجهة ويندوز |
| `android/` | تطبيق أندرويد |
| `static/` | واجهة ويب احتياطية |
