package com.scanny.scanner.activity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.scanny.scanner.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shows the privacy policy bundled with the app (res/raw/privacy_policy.html).
 *
 * <p>It used to load https://scanny.flycricket.io/privacy.html: a template seller's ad-sponsored
 * page, written for another developer, that still described AdMob and log collection and gave
 * admin@admin.com as the contact. A bundled copy always matches this build, and the app no
 * longer needs internet access at all.</p>
 */
public class PrivacyPolicyActivity extends BaseActivity {
    protected WebView web;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_privacy_policy);

        this.web = (WebView) findViewById(R.id.webView);
        findViewById(R.id.iv_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });
        // Static local page: no JavaScript, no file or content access, no network.
        WebSettings settings = this.web.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        // Links (e.g. Google's privacy policy) open in the user's browser, never in this WebView.
        this.web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("https".equals(scheme) || "mailto".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException ignored) {
                        // No browser or mail app; nothing to do.
                    }
                }
                return true;
            }
        });
        this.web.loadDataWithBaseURL(null, policyHtml(), "text/html", "UTF-8", null);
    }

    private String policyHtml() {
        String html;
        try (InputStream in = getResources().openRawResource(R.raw.privacy_policy)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            html = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<p>The privacy policy could not be loaded.</p>";
        }
        String email = getString(R.string.privacy_contact_email).trim();
        String contact = email.isEmpty()
                ? "use the developer contact on the app's Google Play page"
                : "<a href=\"mailto:" + TextUtils.htmlEncode(email) + "\">" + TextUtils.htmlEncode(email) + "</a>";
        return html.replace("{{CONTACT}}", contact);
    }
}
