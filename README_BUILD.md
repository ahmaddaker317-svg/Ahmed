# ADT Pro Android + Firebase FCM

هذا المشروع مبني خصيصًا لـ ADT Pro ويحتوي على:

- Package: `com.adt.pro`
- Firebase Cloud Messaging (FCM)
- تمرير FCM Token تلقائيًا إلى JavaScript عبر:
  - `window.ADT_registerPushToken(token)`
  - `window.Android.getFcmToken()`
- حفظ التوكن محليًا وإعادة تمريره عند فتح التطبيق
- استقبال الإشعارات خارج التطبيق وفي الخلفية
- طلب إذن الإشعارات على Android 13+
- خلفية WebView داكنة `#070A12`
- لا يوجد Pull-to-Refresh
- صفحة انقطاع إنترنت داكنة بدون إظهار رابط الموقع
- آخر نسخة HTML المستخدمة: `ADT_Pro_SECURE_FINAL_PUSH.html`
- ملف `google-services.json` مضمّن لمشروع Firebase `adt-pro0`

## البناء بدون Android Studio
المشروع يحتوي على GitHub Actions في:
`.github/workflows/build-apk.yml`

ارفع محتويات المشروع إلى مستودع GitHub ثم افتح Actions وشغّل:
`Build ADT Pro APK`

بعد اكتمال البناء حمّل Artifact باسم:
`ADT-Pro-APK`

## اختبار التوكن
بعد تثبيت التطبيق، افتحه وسجّل الدخول للفريق، ثم في Supabase نفّذ:

```sql
select team_id,user_id,platform,enabled,length(token) token_length,updated_at
from public.adt_push_tokens
order by updated_at desc;
```

يجب أن يظهر صف للجهاز بدل `No rows returned`.

## Final Supabase migration
بعد رفع وبناء النسخة النهائية، شغّل الملف التالي مرة واحدة في Supabase SQL Editor:
`ADT_FINAL_FEATURES_AND_OWNER_NAME.sql`

هذا التحديث يضيف تفعيل الفواتير المدفوعة عبر لوحة المطور ويصحح اسم مدير فريق المالك فقط إلى «أحمد داكر».
