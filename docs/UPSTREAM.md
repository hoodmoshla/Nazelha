# أصل Nazelha وسجل نقل المرحلة الأولى

## نقطة الأساس

اختيرت نسخة SnapNet `v1.0.5` بالـCommit:

```text
07929caf3cba21ebf7efefd244d939d4887cf4db
```

المصدر: https://github.com/hoodmoshla/SnapNet/tree/07929caf3cba21ebf7efefd244d939d4887cf4db

هذه هي نقطة الأساس الفعلية لفرع التجربة المحلي `feature/direct-http-downloads-stage1-2026-10-27`، وليست `main` الأقدم. عند الفحص كان `main` لـSnapNet عند `9c18cf561edb0c06dafbafa790b37f53242cd3f4`، وكان فرع `feature/snapnet-share-format-ui-2026-10-12` ووسم `v1.0.5` عند نقطة الأساس أعلاه.

## العمل الذي وُجد ونُقل

وُجدت المرحلة الأولى في مساحة المصدر كتغييرات غير محفوظة في Commit: ستة ملفات متتبعة معدّلة وخمسة ملفات Kotlin غير متتبعة. نُسخ تاريخ Git إلى مساحة مستقلة دون روابط hard links، ثم أُزيل remote النسخة المحلية من Nazelha قبل أي عمل جديد، ولم يُضف remote للكتابة إلى SnapNet.

نُقلت الملفات المحلية الإحدى عشرة byte-for-byte، بما فيها الاختبارات الجديدة؛ لم يُكتفَ بفرع GitHub. قبل تغييرات توثيق Nazelha وعزل CI، تطابقت ملفات لقطة المصدر الـ426 بالكامل بين المساحتين. حُفظت بصمات الملفات المنقولة في `docs/phase1/source-migration.json`.

يشمل النقل:

- `DirectHttpLinkResolver.kt` و`DirectHttpDownloader.kt` و`DirectHttpFile.kt`.
- تعديلات `DownloaderV2.kt` و`Task.kt` و`TaskFactory.kt`.
- تعديلات `DownloadDialogV2.kt` و`DownloadDialogViewModel.kt` وموارد النصوص.
- `DirectHttpTransferTest.kt` و`TestHttpServer.kt`.

لم تُنقل مجلدات البناء المؤقتة أو مخازن Gradle أو مفاتيح التوقيع إلى سجل Git. أُعيد استخدام ملف APK المرجعي الموجود كما هو للتحقق والتسليم، دون الادعاء بأنه بناء جديد أو أنه يحمل هوية نزّلها.

## التغييرات الخاصة بالاستقلال

- README جديد لـNazelha، مع أرشفة README الأصلي دون تعديل.
- أرشفة workflows الأصلية بامتداد `.disabled` خارج `.github/workflows` لمنع أي نشر أو إدارة issues موروثة تلقائيًا.
- تجاهل `.local-verification/` في Git؛ لا تُرفع ملفات APK أو السجلات المؤقتة أو مواد التوقيع منه.
- حفظ الترخيص وملفات إشعارات تراخيص المكوّنات وتاريخ المصدر دون إعادة كتابة.

هذه تغييرات في التوثيق وعزل CI فقط. لا تغيّر كود التطبيق أو موارده المضمّنة في APK المرجعي.

## الترخيص والإسناد

ملف `LICENSE` الأصلي هو GNU General Public License v3.0، وهو محفوظ byte-for-byte. أصل SnapNet هو [Seal](https://github.com/JunkFood02/Seal) لـ[JunkFood02](https://github.com/JunkFood02). تبقى إشعارات وتراخيص yt-dlp وyoutubedl-android وCPython وFFmpeg وQuickJS-ng وسائر المكونات في مواقعها الأصلية داخل المشروع، بما فيها `app/src/main/assets/licenses/`.

سجل Git وREADME الأصلي المحفوظ في `docs/upstream/SnapNet-README.md` يحتفظان بإسناد المؤلفين السابقين. إعادة تسمية المشروع لا تعني امتلاك حقوق حصرية في الكود السابق أو إسقاط التزامات إتاحة المصدر الموافق للبناء الموزع.

## حدود الإثبات

تثبت مقارنة البصمات تطابق الملفات التي نُقلت، ولا تثبت وحدها السلوك على الهاتف. يثبت تطابق SHA-256 أن APK المُعاد استخدامه هو نفسه البناء المرجعي، لا أن إعادة بناء جديدة ستكون متطابقة بتًا ببت. نتائج الاختبارات والتحذيرات والحدود التشغيلية مسجّلة في `docs/phase1/verification.md`.
