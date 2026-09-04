package com.adt.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.webkit.URLUtil;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.view.ViewGroup;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import android.app.Activity;
import androidx.core.content.FileProvider;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int REQ_NOTIFICATIONS = 1101;
    private static final String HOME_URL = "file:///android_asset/index.html";
    private volatile String fcmToken = "";
    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQ_FILE_CHOOSER = 1202;
    private static final int REQ_SAVE_FILE = 1203;
    private static final String SB_URL = "https://ufvsxvifbmqsqcugbtlf.supabase.co";
    private static final String SB_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVmdnN4dmlmYm1xc3FjdWdidGxmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY3MDU3NjksImV4cCI6MjEwMjI4MTc2OX0.1KL6y2gH3galMeJC2bUGnIl_flKA7pLZY5C6KYTQ_L0";
    private byte[] pendingFileBytes;
    private String pendingFileName;
    private String pendingFileMime;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        if (Build.VERSION.SDK_INT >= 30) {
            try { getWindow().setDecorFitsSystemWindows(true); } catch (Exception ignored) {}
        }
        createPriceNotificationChannel();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        int topInset = Build.VERSION.SDK_INT >= 35 ? getStatusBarHeightPx() : 0;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        lp.topMargin = topInset;
        root.addView(webView, lp);
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new NativeBridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) MainActivity.this.filePathCallback.onReceiveValue(null);
                MainActivity.this.filePathCallback = filePathCallback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(resolveFileChooserMime(fileChooserParams));
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "تعذر فتح مدير الملفات", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                deliverTokenToWeb();
                installNativePageHooks();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return openExternalUri(request.getUrl());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOfflinePage();
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                if (url != null && url.startsWith("blob:")) {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    fetchBlobForNativeSave(url, fileName, mimetype);
                } else if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimetype);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            URLUtil.guessFileName(url, contentDisposition, mimetype));
                    ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
                }
            } catch (Exception e) {
                Toast.makeText(this, "تعذر تنزيل الملف", Toast.LENGTH_SHORT).show();
            }
        });

        requestNotificationPermission();
        loadStoredToken();
        refreshFcmToken();

        webView.loadUrl(HOME_URL);
    }

    private String resolveFileChooserMime(WebChromeClient.FileChooserParams params) {
        String[] values = params == null ? null : params.getAcceptTypes();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                for (String part : value.split(",")) {
                    String type = part.trim();
                    if (type.equalsIgnoreCase(".json") || type.toLowerCase().contains("json")) {
                        return "application/json";
                    }
                    if (type.indexOf('/') > 0) return type;
                }
            }
        }
        return "*/*";
    }

    private boolean openExternalUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null || "file".equalsIgnoreCase(scheme) ||
                "about".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme) ||
                "blob".equalsIgnoreCase(scheme)) return false;
        String normalized = scheme.toLowerCase();
        if (!("http".equals(normalized) || "https".equals(normalized) ||
                "mailto".equals(normalized) || "tel".equals(normalized) ||
                "sms".equals(normalized) || "intent".equals(normalized) ||
                "market".equals(normalized))) {
            Toast.makeText(this, "نوع الرابط غير مدعوم", Toast.LENGTH_SHORT).show();
            return true;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "لا يوجد تطبيق مناسب لفتح الرابط", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void installNativePageHooks() {
        if (webView == null) return;
        String js = "(function(){if(window.__adtAndroidHooksReady)return;window.__adtAndroidHooksReady=true;"
                + "document.addEventListener('click',function(e){var n=e.target;var a=n&&n.closest?n.closest('a'):null;if(!a)return;"
                + "var h=a.href||'';if((a.target==='_blank'||h.indexOf('wa.me/')>=0)&&/^(https?:|intent:|mailto:|tel:)/i.test(h)){"
                + "e.preventDefault();Android.openExternal(h);}},true);"
                + "var oldOpen=window.open;window.open=function(u,t,f){if(typeof u==='string'&&/^(https?:|intent:|mailto:|tel:)/i.test(u)){"
                + "Android.openExternal(u);return null;}return oldOpen.call(window,u,t,f);};})();";
        webView.evaluateJavascript(js, null);
    }

    private void fetchBlobForNativeSave(String blobUrl, String fileName, String mimeType) {
        if (webView == null) return;
        String js = "(function(){fetch(" + JSONObject.quote(blobUrl) + ").then(function(r){return r.blob();})"
                + ".then(function(b){var x=new FileReader();x.onloadend=function(){var s=String(x.result||'');var i=s.indexOf(',');"
                + "Android.saveBase64File(i>=0?s.slice(i+1):s," + JSONObject.quote(fileName) + ",b.type||"
                + JSONObject.quote(safeMime(mimeType)) + ");};x.readAsDataURL(b);})"
                + ".catch(function(){Android.showNativeToast('تعذر تجهيز الملف');});})();";
        webView.evaluateJavascript(js, null);
    }

    private static String safeFileName(String fileName, String fallback) {
        String result = fileName == null ? "" : fileName.trim();
        result = result.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (result.startsWith(".")) result = result.substring(1);
        if (result.isEmpty()) result = fallback;
        return result.length() > 120 ? result.substring(0, 120) : result;
    }

    private static String safeMime(String mimeType) {
        if (mimeType == null || mimeType.indexOf('/') <= 0) return "application/octet-stream";
        String result = mimeType.split(";", 2)[0].trim();
        return result.isEmpty() ? "application/octet-stream" : result;
    }

    private void requestNativeFileSave(String base64, String fileName, String mimeType) {
        try {
            final byte[] bytes = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
            if (bytes.length == 0) throw new IllegalArgumentException("EMPTY_FILE");
            runOnUiThread(() -> {
                if (pendingFileBytes != null) {
                    Toast.makeText(MainActivity.this, "أكمل حفظ الملف الحالي أولاً", Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingFileBytes = bytes;
                pendingFileName = safeFileName(fileName, "ADT_Pro_File.json");
                pendingFileMime = safeMime(mimeType);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(pendingFileMime);
                intent.putExtra(Intent.EXTRA_TITLE, pendingFileName);
                try {
                    startActivityForResult(intent, REQ_SAVE_FILE);
                } catch (ActivityNotFoundException error) {
                    clearPendingFileSave();
                    Toast.makeText(MainActivity.this, "لا يوجد مدير ملفات متاح", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception error) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذر تجهيز الملف للحفظ", Toast.LENGTH_SHORT).show());
        }
    }

    private void clearPendingFileSave() {
        pendingFileBytes = null;
        pendingFileName = null;
        pendingFileMime = null;
    }

    private void finishBackNavigation() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private int getStatusBarHeightPx() {
        try {
            int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) return getResources().getDimensionPixelSize(resId);
        } catch (Exception ignored) {}
        return 0;
    }

    private void createPriceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    "adt_price_updates", "تحديثات ADT Pro", android.app.NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("إشعارات تحديث الأسعار والتنبيهات المهمة");
            ch.enableVibration(true);
            ch.enableLights(true);
            ch.setLightColor(Color.CYAN);
            nm.createNotificationChannel(ch);
        }
    }

    private void loadStoredToken() {
        fcmToken = getSharedPreferences("adt_push", MODE_PRIVATE).getString("fcm_token", "");
    }

    private void saveToken(String token) {
        if (token == null || token.trim().length() < 20) return;
        fcmToken = token.trim();
        getSharedPreferences("adt_push", MODE_PRIVATE).edit().putString("fcm_token", fcmToken).apply();
        deliverTokenToWeb();
    }

    private void refreshFcmToken() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) saveToken(task.getResult());
        });
    }

    private void deliverTokenToWeb() {
        if (webView == null || fcmToken == null || fcmToken.length() < 20) return;
        final String js = "javascript:(function(){"
                + "var t=" + JSONObject.quote(fcmToken) + ";"
                + "try{localStorage.setItem('atd_fcm_token',t);}catch(e){}"
                + "if(window.ADT_registerPushToken){window.ADT_registerPushToken(t);}"
                + "else if(window.onFCMToken){window.onFCMToken(t);}"
                + "})();";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS &&
                (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "فعّل إشعارات ADT Pro من إعدادات الهاتف لاستقبالها خارج التطبيق", Toast.LENGTH_LONG).show();
        }
    }

    private void showOfflinePage() {
        String html = "<!doctype html><html dir='rtl'><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;background:#070A12;color:#fff;height:100%;font-family:sans-serif}"
                + "body{display:flex;align-items:center;justify-content:center;text-align:center}.box{padding:28px}"
                + "h2{font-size:24px;margin:0 0 12px}p{color:#8E9BAE;font-size:16px;line-height:1.8}"
                + "button{background:#00D2B4;color:#00110f;border:0;border-radius:14px;padding:13px 28px;font-weight:700;font-size:16px}</style></head>"
                + "<body><div class='box'><h2>لا يوجد اتصال بالإنترنت</h2>"
                + "<p>تحقق من اتصال الإنترنت ثم حاول مرة أخرى.</p>"
                + "<button onclick='location.reload()'>إعادة المحاولة</button></div></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStoredToken();
        refreshFcmToken();
        deliverTokenToWeb();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            if (filePathCallback != null) filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            return;
        }

        if (requestCode == REQ_SAVE_FILE) {
            final byte[] bytes = pendingFileBytes;
            final Uri target = resultCode == RESULT_OK && data != null ? data.getData() : null;
            clearPendingFileSave();
            if (target == null || bytes == null) return;
            new Thread(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                    if (output == null) throw new IllegalStateException("OUTPUT_STREAM_UNAVAILABLE");
                    output.write(bytes);
                    output.flush();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "تم حفظ الملف بنجاح", Toast.LENGTH_LONG).show());
                } catch (Exception error) {
                    android.util.Log.e("ADT_FILE", "Native file save failed", error);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذر حفظ الملف", Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        String js = "(function(){try{"
                + "var d=document.getElementById('sidebarDrawer');if(d&&d.classList.contains('active')){"
                + "if(window.closeSideMenu)window.closeSideMenu();else d.classList.remove('active');return true;}"
                + "var blocked={appLockModal:1,teamSuspendedModal:1,onboardingModal:1,subscriptionExpiredModal:1};"
                + "var list=Array.prototype.slice.call(document.querySelectorAll('.modal')).filter(function(m){"
                + "return !blocked[m.id]&&getComputedStyle(m).display!=='none';}).sort(function(a,b){"
                + "return (parseInt(getComputedStyle(b).zIndex,10)||0)-(parseInt(getComputedStyle(a).zIndex,10)||0);});"
                + "if(list.length){if(window.closeModal)window.closeModal(list[0].id);else list[0].style.display='none';return true;}"
                + "if(typeof currentTab!=='undefined'&&currentTab!=='home'&&window.switchTab){window.switchTab('home',false);return true;}"
                + "return false;}catch(e){return false;}})();";
        webView.evaluateJavascript(js, handled -> {
            if ("true".equals(handled)) return;
            finishBackNavigation();
        });
    }

    public class NativeBridge {
        @JavascriptInterface
        public String getFcmToken() {
            return fcmToken == null ? "" : fcmToken;
        }

        @JavascriptInterface
        public String getFirebaseToken() {
            return getFcmToken();
        }

        @JavascriptInterface
        public void bindPushIdentity(String teamId, String userId) { }

        @JavascriptInterface
        public void clearPushIdentity() { }

        @JavascriptInterface
        public String getNotificationPermissionState() {
            return (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) ? "granted" : "denied";
        }

        @JavascriptInterface
        public void requestNotificationPermissionAgain() {
            runOnUiThread(MainActivity.this::requestNotificationPermission);
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public void refreshFcmToken() {
            MainActivity.this.refreshFcmToken();
        }

        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(() -> {
                try {
                    webView.stopLoading();
                    webView.clearHistory();
                    webView.loadUrl(HOME_URL + "?t=" + System.currentTimeMillis());
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> {
                try {
                    openExternalUri(Uri.parse(url == null ? "" : url.trim()));
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void saveBase64File(String base64, String fileName, String mimeType) {
            requestNativeFileSave(base64, fileName, mimeType);
        }

        @JavascriptInterface
        public void showNativeToast(String message) {
            final String safeMessage = message == null ? "" : message.trim();
            if (safeMessage.isEmpty()) return;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, safeMessage, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void triggerPricePush(String teamId, String actorUserId) {
            if (teamId == null || actorUserId == null || teamId.trim().isEmpty() || actorUserId.trim().isEmpty()) return;
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(SB_URL + "/functions/v1/adt-send-price-push");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(15000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("apikey", SB_ANON);
                    conn.setRequestProperty("Authorization", "Bearer " + SB_ANON);
                    String body = new JSONObject().put("team_id", teamId).put("actor_user_id", actorUserId).toString();
                    try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) android.util.Log.w("ADT_PUSH", "Edge response=" + code);
                } catch (Exception e) {
                    android.util.Log.e("ADT_PUSH", "Native dispatch failed", e);
                } finally { if (conn != null) conn.disconnect(); }
            }).start();
        }

        @JavascriptInterface
        public void sharePdfBase64(String base64, String fileName) {
            try {
                String safeName = safeFileName(fileName, "ADT_Pro.pdf");
                if (!safeName.toLowerCase().endsWith(".pdf")) safeName += ".pdf";
                byte[] bytes = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
                if (bytes.length == 0) throw new IllegalArgumentException("EMPTY_PDF");
                File dir = new File(getCacheDir(), "shared_pdfs");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("CACHE_DIR_FAILED");
                File out = new File(dir, safeName); try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(bytes); }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName()+".fileprovider", out);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/pdf");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newUri(getContentResolver(), safeName, uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    try {
                        Intent chooser = Intent.createChooser(send, "مشاركة ملف PDF");
                        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(chooser);
                    } catch (ActivityNotFoundException error) {
                        Toast.makeText(MainActivity.this, "لا يوجد تطبيق متاح للمشاركة", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذر مشاركة ملف PDF", Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void savePdfBase64(String base64, String fileName) {
            String safeName = safeFileName(fileName, "ADT_Pro.pdf");
            if (!safeName.toLowerCase().endsWith(".pdf")) safeName += ".pdf";
            requestNativeFileSave(base64, safeName, "application/pdf");
        }
    }
}
