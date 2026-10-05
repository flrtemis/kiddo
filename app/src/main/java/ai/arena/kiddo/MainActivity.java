package ai.arena.kiddo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Kiddo does exactly one thing: it shows {@link #START_URL} in a WebView.
 *
 * <p>Everything else in this class exists to make that one page behave like a real app: back
 * walks through history before it exits, the microphone works when the page asks for it,
 * links that leave the site open in the browser, and a dead network shows a retry screen
 * instead of Chromium's sad dinosaur.
 */
public class MainActivity extends Activity {

    /** The one thing this app displays. */
    static final String START_URL = "https://arena.ai/agent";

    /** This host and its subdomains stay inside the WebView; anything else opens in the browser. */
    static final String IN_APP_HOST = "arena.ai";

    private static final String TAG = "Kiddo";
    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final int REQUEST_MICROPHONE = 1002;
    private static final String STATE_URL = "kiddo.last_url";

    private WebView webView;
    private ProgressBar progressBar;
    private View errorView;
    private TextView errorTitle;
    private TextView errorMessage;
    private FrameLayout fullscreenContainer;

    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingMicRequest;
    private android.window.OnBackInvokedCallback backInvokedCallback;

    private boolean pageFailed;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress);
        errorView = findViewById(R.id.error_view);
        errorTitle = findViewById(R.id.error_title);
        errorMessage = findViewById(R.id.error_message);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

        Button retry = findViewById(R.id.error_retry);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                webView.reload();
            }
        });

        prepareWebView(webView);
        registerBackHandling();

        String url = savedInstanceState != null ? savedInstanceState.getString(STATE_URL, START_URL) : START_URL;
        webView.loadUrl(url);
    }

    /** Shared by the first WebView and by any replacement after a renderer crash. */
    @SuppressLint("SetJavaScriptEnabled")
    private void prepareWebView(WebView view) {
        WebSettings settings = view.getSettings();

        // Required for a modern web app: scripting plus the storage it keeps state in.
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Never mix in plain HTTP sub-resources; the app is HTTPS only.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Let the page darken itself when the system theme is dark (API 33+).
            settings.setAlgorithmicDarkeningAllowed(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(view, true);

        view.setWebViewClient(new PageClient());
        view.setWebChromeClient(new ChromeClient());
        view.setDownloadListener(new BrowserDownloadListener());
    }

    private void registerBackHandling() {
        // Android 13+ delivers back through the OnBackInvokedDispatcher; older releases still
        // call onBackPressed(), which is overridden below.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = new android.window.OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    handleBack();
                }
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        webView.pauseTimers();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_URL, currentUrl());
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            backInvokedCallback = null;
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- back

    private void handleBack() {
        if (fullscreenView != null) {
            hideFullscreenView();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    // ---------------------------------------------------------------- activity results

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileChooserCallback = null;
            }
            return;
        }
        if (requestCode == REQUEST_MICROPHONE) {
            PermissionRequest request = pendingMicRequest;
            pendingMicRequest = null;
            if (request != null) {
                if (resultCode == Activity.RESULT_OK
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    request.deny();
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- helpers

    private String currentUrl() {
        if (webView == null) {
            return START_URL;
        }
        String url = webView.getUrl();
        return url != null ? url : START_URL;
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void showError(int titleRes, int messageRes) {
        errorTitle.setText(titleRes);
        errorMessage.setText(messageRes);
        errorView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
    }

    private void showFullscreenView(View view, WebChromeClient.CustomViewCallback callback) {
        fullscreenView = view;
        fullscreenCallback = callback;
        fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenContainer.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
    }

    private void hideFullscreenView() {
        if (fullscreenView == null) {
            return;
        }
        fullscreenContainer.removeView(fullscreenView);
        fullscreenContainer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        fullscreenView = null;
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
    }

    private void openExternally(Uri uri) {
        Intent intent;
        if ("intent".equalsIgnoreCase(uri.getScheme())) {
            try {
                intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
            } catch (Exception e) {
                Log.w(TAG, "Unparsable intent:// url: " + uri, e);
                return;
            }
        } else {
            intent = new Intent(Intent.ACTION_VIEW, uri);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean staysInApp(Uri uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.US);
        return host.equals(IN_APP_HOST) || host.endsWith("." + IN_APP_HOST);
    }

    /** Recreate the WebView after Chromium's renderer died; otherwise the app is stuck blank. */
    private void recreateWebView() {
        ViewGroup parent = (ViewGroup) webView.getParent();
        int index = parent.indexOfChild(webView);
        ViewGroup.LayoutParams layout = webView.getLayoutParams();
        String url = currentUrl();

        parent.removeView(webView);
        webView.destroy();

        WebView fresh = new WebView(this);
        fresh.setLayoutParams(layout);
        parent.addView(fresh, index);

        webView = fresh;
        prepareWebView(fresh);
        hideError();
        fresh.loadUrl(url);
    }

    // ---------------------------------------------------------------- WebViewClient

    private class PageClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);

            if ("http".equals(scheme) || "https".equals(scheme)) {
                if (staysInApp(uri)) {
                    return false; // load it here
                }
                openExternally(uri);
                return true;
            }
            if ("intent".equals(scheme) || "mailto".equals(scheme) || "tel".equals(scheme)
                    || "sms".equals(scheme) || "geo".equals(scheme)) {
                openExternally(uri);
                return true;
            }
            // Unknown schemes (custom app deep links): swallow rather than show a load error.
            Log.i(TAG, "Ignoring unsupported scheme: " + scheme);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageFailed = false;
            hideError();
            progressBar.setProgress(0);
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progressBar.setVisibility(View.GONE);
            if (pageFailed) {
                if (isOnline()) {
                    showError(R.string.error_load_title, R.string.error_load_message);
                } else {
                    showError(R.string.error_offline_title, R.string.error_offline_message);
                }
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // Only main-frame failures blank the page; sub-resource errors are noise.
            if (request.isForMainFrame()) {
                pageFailed = true;
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // Never continue past a certificate we cannot verify.
            handler.cancel();
            pageFailed = true;
            showError(R.string.error_untrusted_title, R.string.error_untrusted_message);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.w(TAG, "WebView renderer died (crashed=" + detail.didCrash() + "), recreating");
            recreateWebView();
            return true;
        }
    }

    // ---------------------------------------------------------------- WebChromeClient

    private class ChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            if (newProgress >= 100) {
                progressBar.setVisibility(View.GONE);
            }
        }

        /** window.open / target=_blank: route the resulting navigation instead of failing. */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            final WebView popup = new WebView(MainActivity.this);
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    if (staysInApp(uri)) {
                        webView.loadUrl(uri.toString());
                    } else {
                        openExternally(uri);
                    }
                    popup.destroy();
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(null);
            }
            fileChooserCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQUEST_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                fileChooserCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, R.string.no_browser, Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    boolean wantsMic = false;
                    boolean wantsSomethingElse = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            wantsMic = true;
                        } else {
                            wantsSomethingElse = true;
                        }
                    }

                    if (wantsMic
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        pendingMicRequest = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                                REQUEST_MICROPHONE);
                        return;
                    }

                    if (wantsMic && !wantsSomethingElse) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        // Camera and friends are out of scope for this app.
                        request.deny();
                    }
                }
            });
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden();
                return;
            }
            showFullscreenView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            hideFullscreenView();
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            Log.d(TAG, consoleMessage.messageLevel() + " " + consoleMessage.message()
                    + " (" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + ")");
            return true;
        }
    }

    // ---------------------------------------------------------------- downloads

    private class BrowserDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            // A WebView cannot write files; let a real browser handle the download.
            openExternally(Uri.parse(url));
        }
    }
}
