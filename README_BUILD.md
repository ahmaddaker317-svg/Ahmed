# ADT Stock V168 — GitHub Ready

هذه النسخة محدّثة من مشروع GitHub القديم V145 إلى آخر واجهة **ADT Stock V168** مع الحفاظ على مشروع Android وإعدادات Firebase الأصلية والحزمة `com.adt.pro`.

## الموجود في V168

- آخر نسخة HTML للتطبيق داخل `app/src/main/assets/index.html`.
- الشعار الحالي بخلفية شفافة.
- تسجيل دخول موحّد للمدير والعضو المعتمد من نفس واجهة تسجيل الدخول.
- إصلاح انتقال بحث الجرد بين واجهتي الجرد.
- تسريع إضافة الصنف في جرد المخزون الفعلي.
- قسم **المبيعات اليومية** مع الإجمالي الحالي، وقت كل عملية، الملاحظات، والتصفية الدورية مع حفظ سجل التصفيات السابقة.
- تحسين واجهة المبيعات اليومية.
- ترتيب كروت الصفحة الرئيسية حسب الترتيب المعتمد في V168.
- الحفاظ على Firebase Messaging والإشعارات الخارجية و`google-services.json` وNative Bridge بدون حذف.

## SQL المطلوب

إذا لم تكن شغّلت التعديلات من قبل، شغّل بالترتيب في Supabase SQL Editor:

1. `V165_MEMBER_UNIFIED_LOGIN.sql`
2. `V166_DAILY_SALES.sql`

ملفات SQL القديمة بالمشروع بقيت كما هي ولم يتم حذفها.

## إصدار Android

- `versionCode 168`
- `versionName 1.68.0`
- package: `com.adt.pro`

## البناء على GitHub

ارفع محتويات هذا ZIP إلى فرع `main`. Workflow الموجود في:

`.github/workflows/main.yml`

سيبني Debug APK ويرفع Artifact باسم:

`ADT-Stock-v168-APK`
