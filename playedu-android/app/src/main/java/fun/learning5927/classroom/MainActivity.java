package fun.learning5927.classroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private String server;
    private WebView webView;
    private FrameLayout webContainer, fullscreenContainer;
    private View normalContent, errorPanel;
    private ProgressBar progress;
    private TextView errorMessage;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean foreground, pageFailed, closing;
    private int navigationGeneration;
    private final Runnable pauseFallback = () -> { if (!foreground && webView != null) webView.onPause(); };
    private static final String CHECKPOINT_SCRIPT = "(function(){window.dispatchEvent(new Event('playedu:app-background'));document.querySelectorAll('video').forEach(function(v){v.pause();});return true;})()";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("classroom", MODE_PRIVATE);
        try { server = ServerAddress.normalize(preferences.getString("server", BuildConfig.DEFAULT_SERVER)); }
        catch (IllegalArgumentException exception) { server = BuildConfig.DEFAULT_SERVER; }
        setContentView(R.layout.activity_main);
        webContainer = findViewById(R.id.web_container);
        fullscreenContainer = findViewById(R.id.fullscreen_container);
        normalContent = findViewById(R.id.normal_content);
        errorPanel = findViewById(R.id.error_panel);
        errorMessage = findViewById(R.id.error_message);
        progress = findViewById(R.id.progress);
        findViewById(R.id.retry).setOnClickListener(v -> loadHome());
        findViewById(R.id.change_server).setOnClickListener(v -> showServerDialog());
        findViewById(R.id.menu).setOnClickListener(this::showMenu);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        createWebView();
        loadHome();
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setId(R.id.classroom_webview);
        webContainer.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSafeBrowsingEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString() + " ClassroomTablet/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setWebViewClient(new ClassroomClient());
        webView.setWebChromeClient(new ClassroomChrome());
    }

    private void loadHome() {
        if (webView == null) createWebView();
        hideFullscreen();
        pageFailed = false;
        errorPanel.setVisibility(View.GONE);
        webView.loadUrl(server);
    }

    private final class ClassroomClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (ServerAddress.isInternal(server, url)) return false;
            if (request.isForMainFrame() && request.hasGesture() && ServerAddress.isExternalWebLink(url)) {
                new AlertDialog.Builder(MainActivity.this).setTitle(R.string.external_title)
                        .setMessage(R.string.external_message).setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.open, (dialog, which) -> openBrowser(url)).show();
            }
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            if (!ServerAddress.isInternal(server, url)) {
                view.stopLoading(); showError(R.string.blocked_link); return;
            }
            navigationGeneration++;
            pageFailed = false;
            errorPanel.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (!pageFailed) errorPanel.setVisibility(View.GONE);
            progress.setVisibility(View.INVISIBLE);
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showError(R.string.connection_message);
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 400) showError(R.string.connection_message);
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
            sslHandler.cancel();
            showError(R.string.certificate_message);
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            hideFullscreen();
            webContainer.removeView(view);
            view.destroy();
            webView = null;
            showError(R.string.renderer_message);
            return true;
        }
    }

    private final class ClassroomChrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int value) {
            progress.setProgress(value);
            if (value == 100) progress.setVisibility(View.INVISIBLE);
        }

        @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }

        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view;
            customCallback = callback;
            normalContent.setVisibility(View.GONE);
            fullscreenContainer.addView(view, new FrameLayout.LayoutParams(-1, -1));
            fullscreenContainer.setVisibility(View.VISIBLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override public void onHideCustomView() { hideFullscreen(); }
    }

    private void hideFullscreen() {
        if (customView == null) return;
        fullscreenContainer.removeAllViews();
        fullscreenContainer.setVisibility(View.GONE);
        normalContent.setVisibility(View.VISIBLE);
        customView = null;
        WebChromeClient.CustomViewCallback callback = customCallback;
        customCallback = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (callback != null) callback.onCustomViewHidden();
    }

    private void showError(int message) {
        pageFailed = true;
        progress.setVisibility(View.INVISIBLE);
        errorMessage.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.home);
        menu.getMenu().add(0, 2, 1, R.string.refresh);
        menu.getMenu().add(0, 3, 2, R.string.server_settings);
        menu.getMenu().add(0, 4, 3, R.string.about);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: checkpoint(this::loadHome); break;
                case 2: new AlertDialog.Builder(this).setTitle(R.string.refresh).setMessage(R.string.refresh_message)
                        .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.refresh, (d, w) ->
                            checkpoint(() -> { if (webView != null) webView.reload(); else loadHome(); })).show(); break;
                case 3: showServerDialog(); break;
                case 4: new AlertDialog.Builder(this).setTitle(R.string.about)
                        .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME, server))
                        .setPositiveButton(android.R.string.ok, null).show(); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private void showServerDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(server);
        input.setHint(R.string.address_hint);
        int padding = (int)(20 * getResources().getDisplayMetrics().density);
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(padding, 0, padding, 0);
        holder.addView(input, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.server_settings)
                .setMessage(R.string.change_address).setView(holder)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String next;
            try { next = ServerAddress.normalize(input.getText().toString()); }
            catch (IllegalArgumentException exception) { input.setError(getString(R.string.invalid_address)); return; }
            if (next.equals(server)) { dialog.dismiss(); return; }
            dialog.dismiss();
            checkpoint(() -> changeServer(next));
        }));
        dialog.show();
    }

    private void changeServer(String next) {
        hideFullscreen();
        if (webView != null) { webView.stopLoading(); webContainer.removeView(webView); webView.destroy(); webView = null; }
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(ignored -> {
            CookieManager.getInstance().flush();
            server = next;
            preferences.edit().putString("server", server).apply();
            loadHome();
        });
    }

    private void openBrowser(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException exception) { Toast.makeText(this, R.string.browser_missing, Toast.LENGTH_LONG).show(); }
    }

    private void checkpoint(Runnable after) {
        if (webView == null || !ServerAddress.isInternal(server, webView.getUrl())) { after.run(); return; }
        int generation = navigationGeneration;
        boolean[] done = {false};
        Runnable complete = () -> { if (!done[0]) { done[0] = true; if (generation == navigationGeneration) after.run(); } };
        webView.evaluateJavascript(CHECKPOINT_SCRIPT, value -> handler.postDelayed(complete, 200));
        handler.postDelayed(complete, 600);
    }

    private void handleBack() {
        if (customView != null) { hideFullscreen(); return; }
        if (webView != null && webView.canGoBack()) { checkpoint(() -> webView.goBack()); return; }
        new AlertDialog.Builder(this).setTitle(R.string.exit_title).setMessage(R.string.exit_message)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.exit, (d, w) -> {
                    if (!closing) { closing = true; checkpoint(this::finish); }
                }).show();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        handler.removeCallbacks(pauseFallback);
        if (webView != null) webView.onResume();
    }

    @Override protected void onPause() {
        foreground = false;
        if (webView != null) {
            WebView pausedView = webView;
            checkpoint(() -> { if (!foreground && webView == pausedView) webView.onPause(); });
            handler.postDelayed(pauseFallback, 700);
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideFullscreen();
        if (webView != null) { webContainer.removeView(webView); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
