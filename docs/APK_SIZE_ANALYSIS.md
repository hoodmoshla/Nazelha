# تحليل حجم APK التجريبي — Nazelha

## النتيجة النهائية

حجم APK ARM64 الذي أنشأه GitHub Actions للإصدار `1.0.7` هو **70,407,516 بايت (70.41 MB عشريًا، 67.15 MiB)**. مقارنةً بالنسخة المنشورة `1.0.6`، انخفض الحجم بمقدار **17,230,584 بايت (17.23 MB)، أي 19.66%**.

| الإصدار | حجم APK | التغيير |
|---|---:|---:|
| [1.0.6](https://github.com/hoodmoshla/Nazelha/releases/download/nazelha-preview-v1.0.6-2026-10-27/Nazelha-1.0.6-debug.apk) | 87,638,100 بايت (87.64 MB) | خط الأساس |
| [1.0.7](https://github.com/hoodmoshla/Nazelha/releases/download/nazelha-preview-v1.0.7-2026-10-27/Nazelha-1.0.7-debug.apk) | 70,407,516 بايت (70.41 MB) | أصغر بـ17,230,584 بايت (19.66%) |

## مكونات 1.0.7

| القسم المضغوط داخل APK | الحجم | النسبة من APK |
|---|---:|---:|
| المكتبات الأصلية ARM64 | 55,724,259 بايت | 79.15% |
| ملفات DEX | 8,429,303 بايت | 11.97% |
| موارد `res/`، ومنها yt-dlp | 3,699,080 بايت | 5.25% |
| جدول موارد Android `resources.arsc` | 2,328,576 بايت | 3.31% |
| بقية الإدخالات | 70,114 بايت | 0.10% |

أكبر المكتبات هي `libffmpeg.zip.so` (35,445,213 بايت)، و`libpython.zip.so` (14,056,980 بايت)، و`libaria2c.zip.so` (4,246,281 بايت). بقيت FFmpeg وPython وyt-dlp وaria2 وQuickJS ضمن الحزمة لأنها تخدم دمج الوسائط وتنزيل الفيديو والصوت والملفات؛ لم يُحذف محرك أساسي لمجرد خفض الرقم.

## سبب الانخفاض والتحقق من الوظائف

فُعّل R8 وتقليص الموارد لبناء المعاينة. انخفض حجم ملفات DEX المضغوطة من 25,139,876 بايت في 1.0.6 إلى 8,429,303 بايت في 1.0.7. اجتاز البناء المصغّر اختبارات الوحدة واختبارات الواجهة على محاكي Android 15 في [تشغيل GitHub Actions النهائي](https://github.com/hoodmoshla/Nazelha/actions/runs/40580027983)، ولم يظهر خلل وظيفي منسوب إلى R8؛ لذلك بقي التصغير مفعّلًا.

## بصمة APK والتوقيع

- الملف: `Nazelha-1.0.7-debug.apk`، لمعمارية `arm64-v8a` فقط.
- SHA-256: `22ef551cfc7a9e0600bd99e7c8a2292e074f98398c1a449e2cbe6cd45575be4b`.
- التوقيع: شهادة Android Debug، مخطط V2؛ بصمة شهادة SHA-256 هي `e300034cda6669f69856cf3b0df8a156ce8fbafa4f68a1ffddcf8015a603967d`.
- تطابقت البصمة والحجم مع `apk-size-profile.json` ومع أصل APK المنشور في GitHub.

روابط الإصدار: [صفحة prerelease 1.0.7](https://github.com/hoodmoshla/Nazelha/releases/tag/nazelha-preview-v1.0.7-2026-10-27)، [APK](https://github.com/hoodmoshla/Nazelha/releases/download/nazelha-preview-v1.0.7-2026-10-27/Nazelha-1.0.7-debug.apk)، [ملف SHA-256](https://github.com/hoodmoshla/Nazelha/releases/download/nazelha-preview-v1.0.7-2026-10-27/Nazelha-1.0.7-debug.apk.sha256)، [تفاصيل الحجم](https://github.com/hoodmoshla/Nazelha/releases/download/nazelha-preview-v1.0.7-2026-10-27/apk-size-profile.json)، و[تقرير التحقق من التوقيع والحزمة](https://github.com/hoodmoshla/Nazelha/releases/download/nazelha-preview-v1.0.7-2026-10-27/apk-verification.txt).

## ملاحظة عن نشر الأصل

احتفظ CI بالـAPK داخل مسار متداخل في Artifact، بينما كان نمط رفع Release يبحث في الجذر؛ لذلك لم يلتقطه النشر الآلي الأول رغم نجاح الاختبارات. أُعيد التحقق من **الملف نفسه الذي بناه CI** ثم أُرفق مع checksum بالإصدار ذاته. كما عُدّل workflow لتسطيح المسار والتحقق من APK وبصمته وملف التقرير قبل النشر، وليفشل بوضوح إذا غاب الملف.

هذا إصدار Debug تجريبي، ولا يقبل التحديث مباشرة فوق 1.0.6 بسبب اختلاف شهادة التوقيع. لم تُحذف نسخة 1.0.6 أو ملفاتها. لم يُختبر التثبيت على هاتف المستخدم؛ التحقق الآلي كان على CI ومحاكي Android 15.
