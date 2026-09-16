package com.example.floatingcandlescanner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class BrokerActivity extends Activity {
    private WebView web;
    private EditText address;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11,18,32));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8),dp(8),dp(8),dp(8));

        Button back = smallButton("←");
        Button refresh = smallButton("↻");
        Button chrome = smallButton("Chrome");

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(Color.LTGRAY);
        address.setBackgroundColor(Color.rgb(23,32,51));
        address.setPadding(dp(10),0,dp(10),0);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0,dp(46),1f);
        alp.setMargins(dp(6),0,dp(6),0);
        address.setLayoutParams(alp);

        bar.addView(back);
        bar.addView(address);
        bar.addView(refresh);
        bar.addView(chrome);
        root.addView(bar,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(62)));

        TextView notice = new TextView(this);
        notice.setText("Sign in directly on the broker website. The scanner does not read or store your broker password.");
        notice.setTextColor(Color.rgb(251,191,36));
        notice.setTextSize(12);
        notice.setPadding(dp(10),0,dp(10),dp(8));
        root.addView(notice);

        web = new WebView(this);
        root.addView(web,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        setContentView(root);

        configureWebView();

        back.setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack(); else finish();
        });
        refresh.setOnClickListener(v -> web.reload());
        chrome.setOnClickListener(v -> {
            String url = web.getUrl();
            if (url == null || url.isEmpty()) url = address.getText().toString();
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception ignored) {}
        });

        address.setOnEditorActionListener((v,actionId,event) -> {
            load(address.getText().toString());
            return true;
        });

        String saved = getSharedPreferences("scanner",MODE_PRIVATE)
                .getString("broker_url","https://pocketoption.com/");
        address.setText(saved);
        load(saved);
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web,true);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                address.setText(request.getUrl().toString());
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                address.setText(url);
                getSharedPreferences("scanner",MODE_PRIVATE)
                        .edit().putString("broker_url",url).apply();
            }
        });
    }

    private void load(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        getSharedPreferences("scanner",MODE_PRIVATE)
                .edit().putString("broker_url",url).apply();
        web.loadUrl(url);
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(64),dp(46)));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (web != null) {
            CookieManager.getInstance().flush();
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.clearHistory();
            web.removeAllViews();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
