package com.mozhi.assistant;

import android.content.Context;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;

/** One renderer shared by live replies, saved history and rendering tests. */
public final class ReplyMarkdown {
    private ReplyMarkdown() {}
    public static Markwon create(Context context, Consumer<String> onLink) {
        return create(context,(view,url)->onLink.accept(url));
    }
    public static Markwon create(Context context, BiConsumer<android.view.View,String> onLink) {
        return Markwon.builder(context)
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context))
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .usePlugin(new AbstractMarkwonPlugin() {
                @Override public void configureConfiguration(MarkwonConfiguration.Builder builder) {
                    builder.linkResolver(onLink::accept);
                }
                @Override public void configureTheme(io.noties.markwon.core.MarkwonTheme.Builder builder) {
                    builder.linkColor(0xff956047).isLinkUnderlined(true);
                }
                @Override public void afterSetText(android.widget.TextView view) {
                    // Selectable TextView installs ArrowKeyMovementMethod. Markwon preserves that
                    // non-null method, so links look correct but taps never reach the resolver.
                    view.setTextIsSelectable(false);
                    view.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    view.setLinksClickable(true);
                }
            }).build();
    }
}
