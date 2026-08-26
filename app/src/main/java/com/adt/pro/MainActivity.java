package com.adt.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.provider.MediaStore;
import android.content.ContentValues;
import java.io.OutputStream;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.view.ViewGroup;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    private static final String SB_URL = "https://ufvsxvifbmqsqcugbtlf.supabase.co";
    private static final String SB_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVmdnN4dmlmYm1xc3FjdWdidGxmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY3MDU3NjksImV4cCI6MjEwMjI4MTc2OX0.1KL6y2gH3galMeJC2bUGnIl_flKA7pLZY5C6KYTQ_L0";

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
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
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
                    intent.setType("image/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "تعذر فتح الصور", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                deliverTokenToWeb();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    view.loadUrl(uri.toString());
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOfflinePage();
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimetype);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ADT_Pro_File");
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
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            if (filePathCallback != null) filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
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
                String safeName = (fileName == null || fileName.trim().isEmpty()) ? "ADT_Pro.pdf" : fileName.replaceAll("[\\/:*?\"<>|]", "_");
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "shared_pdfs"); if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, safeName); try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(bytes); }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName()+".fileprovider", out);
                Intent send = new Intent(Intent.ACTION_SEND); send.setType("application/pdf"); send.putExtra(Intent.EXTRA_STREAM, uri); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "مشاركة ملف PDF")));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذر مشاركة ملف PDF", Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void savePdfBase64(String base64, String fileName) {
            try {
                String safeName = (fileName == null || fileName.trim().isEmpty()) ? "ADT_Pro.pdf" : fileName.replaceAll("[\\/:*?\"<>|]", "_");
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new Exception("DOWNLOAD_URI_FAILED");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) throw new Exception("OUTPUT_STREAM_FAILED");
                        os.write(bytes);
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, safeName);
                    try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(bytes); }
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "تم حفظ PDF في التنزيلات", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذر حفظ ملف PDF", Toast.LENGTH_LONG).show());
            }
        }
    }
}
