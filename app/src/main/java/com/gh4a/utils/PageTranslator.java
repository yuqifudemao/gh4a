package com.gh4a.utils;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Translates English text visible on the current screen without sending it to a server. */
public final class PageTranslator {
    private static final String COLLECT_TEXT_NODES =
            "(function(){window.__octoTranslationNodes=[];var w=document.createTreeWalker(" +
            "document.body,NodeFilter.SHOW_TEXT);var n,a=[];while(n=w.nextNode()){var p=n.parentNode;" +
            "if(!p||/^(SCRIPT|STYLE|CODE|PRE|TEXTAREA|INPUT)$/i.test(p.nodeName))continue;" +
            "var t=n.nodeValue.trim();if(/[A-Za-z]{2}/.test(t)){window.__octoTranslationNodes.push(n);" +
            "a.push(n.nodeValue);}}window.__octoTranslationOriginal=a.slice();return JSON.stringify(a);})()";
    private static final String RESTORE_WEB_TEXT =
            "(function(){if(!window.__octoTranslationNodes)return;window.__octoTranslationNodes." +
            "forEach(function(n,i){n.nodeValue=window.__octoTranslationOriginal[i];});})()";

    private final Activity mActivity;
    private final Translator mTranslator;
    private final WeakHashMap<TextView, CharSequence> mOriginalText = new WeakHashMap<>();
    private final List<WebView> mTranslatedWebViews = new ArrayList<>();
    private boolean mTranslated;

    public PageTranslator(@NonNull Activity activity) {
        mActivity = activity;
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build();
        mTranslator = Translation.getClient(options);
    }

    public boolean isTranslated() {
        return mTranslated;
    }

    public void translate(@NonNull View root, @NonNull Runnable onComplete,
            @NonNull Consumer<Exception> onFailure) {
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        mTranslator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> translateViews(root, onComplete, onFailure))
                .addOnFailureListener(onFailure::accept);
    }

    public void restore(@NonNull View root) {
        for (TextView view : new ArrayList<>(mOriginalText.keySet())) {
            CharSequence original = mOriginalText.get(view);
            if (view != null && original != null) {
                view.setText(original);
            }
        }
        for (WebView webView : mTranslatedWebViews) {
            if (webView != null) {
                webView.evaluateJavascript(RESTORE_WEB_TEXT, null);
            }
        }
        mOriginalText.clear();
        mTranslatedWebViews.clear();
        mTranslated = false;
    }

    private void translateViews(View root, Runnable onComplete, Consumer<Exception> onFailure) {
        List<TextView> textViews = new ArrayList<>();
        List<WebView> webViews = new ArrayList<>();
        collectViews(root, textViews, webViews);
        AtomicInteger pending = new AtomicInteger(textViews.size() + webViews.size());
        if (pending.get() == 0) {
            mTranslated = true;
            onComplete.run();
            return;
        }
        Runnable finishedOne = () -> {
            if (pending.decrementAndGet() == 0) {
                mTranslated = true;
                onComplete.run();
            }
        };
        for (TextView view : textViews) {
            CharSequence original = view.getText();
            mOriginalText.put(view, original);
            mTranslator.translate(original.toString())
                    .addOnSuccessListener(text -> {
                        if (!mActivity.isFinishing()) view.setText(text);
                        finishedOne.run();
                    })
                    .addOnFailureListener(error -> finishedOne.run());
        }
        for (WebView webView : webViews) {
            translateWebView(webView, finishedOne);
        }
    }

    private void translateWebView(WebView webView, Runnable finished) {
        webView.evaluateJavascript(COLLECT_TEXT_NODES, value -> {
            try {
                String decoded = value == null ? "[]" : new JSONArray("[" + value + "]").getString(0);
                JSONArray nodes = new JSONArray(decoded);
                if (nodes.length() == 0) {
                    finished.run();
                    return;
                }
                mTranslatedWebViews.add(webView);
                AtomicInteger pending = new AtomicInteger(nodes.length());
                for (int i = 0; i < nodes.length(); i++) {
                    final int index = i;
                    mTranslator.translate(nodes.getString(i))
                            .addOnSuccessListener(text -> webView.evaluateJavascript(
                                    "window.__octoTranslationNodes[" + index + "].nodeValue=" +
                                            JSONObject.quote(text), null))
                            .addOnCompleteListener(task -> {
                                if (pending.decrementAndGet() == 0) finished.run();
                            });
                }
            } catch (Exception ignored) {
                finished.run();
            }
        });
    }

    private static void collectViews(View view, List<TextView> textViews,
            List<WebView> webViews) {
        if (view instanceof WebView) {
            webViews.add((WebView) view);
            return;
        }
        if (view instanceof TextView && !(view instanceof EditText)) {
            String text = ((TextView) view).getText().toString().trim();
            if (shouldTranslate(text)) textViews.add((TextView) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectViews(group.getChildAt(i), textViews, webViews);
            }
        }
    }

    private static boolean shouldTranslate(String text) {
        if (text.length() < 2 || !text.matches(".*[A-Za-z]{2}.*")) return false;
        if (text.matches("(?i)^(https?://|git@|[a-f0-9]{7,40}$).*")) return false;
        return !text.matches("^[\\w./@:#%+~=\\-]+$");
    }
}
