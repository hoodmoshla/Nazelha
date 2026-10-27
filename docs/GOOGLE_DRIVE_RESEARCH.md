# متطلبات Google Drive الرسمية التي جرى التحقق منها

## التنزيل من Drive API

واجهة `files.get` تقبل `alt=media` لإرجاع محتوى ملف Drive المخزّن. توثيق Google يذكر أن تنزيل المحتوى بهذه الطريقة يتطلب أحد نطاقات OAuth المدرجة، ومنها `drive.readonly`؛ وتصدير مستندات Google Workspace يتم عبر `files.export` بدلًا من ذلك. لذلك لا تكفي إعادة كتابة رابط المشاركة إلى رابط تنزيل عام لإثبات تكامل API رسمي أو تجاوز صلاحيات الملف.

المصدر: [Google Drive API — files.get](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/get)

## تفويض تطبيق Android

توصي وثائق Android باستخدام `AuthorizationClient` لطلب صلاحيات بيانات Google عند تنفيذ الإجراء الذي يحتاج إليها. إعداد Android OAuth client يتطلب معرّف حزمة التطبيق وبصمة شهادة SHA‑1، مع تهيئة مشروع Google Cloud وشاشة الموافقة والمعلومات المطلوبة. لا يحتوي فرع Nazelha الذي تمت مراجعته على تكامل Drive أو إعداد OAuth/API مرتبط بالحزمة التجريبية `com.snapnet.preview.debug`؛ لذلك لا يمكن وصف دعم Drive الموثّق على أنه مكتمل قبل إعداد المشروع وطلب التفويض من المستخدم داخل التطبيق.

المصدر: [Android — Authorize access to user data](https://developer.android.com/identity/authorization)

## حدود التنفيذ

يجب ألا تُرسل Cookies أو رموز OAuth عبر تحويل إلى نطاق آخر، وألا تُسجَّل الرموز في السجلات أو تحفظ داخل ملفات المصدر. روابط Drive الخاصة أو المقيدة تحتاج تفويضًا سليمًا؛ إذا لم يكن التفويض مهيّأ، فالنتيجة الصادقة هي إظهار أن المصدر يتطلب تسجيل/إعدادًا، لا الادعاء بأن رابط المشاركة مدعوم.
