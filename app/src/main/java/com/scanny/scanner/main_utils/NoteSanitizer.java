package com.scanny.scanner.main_utils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cleans note HTML before it is loaded into the JavaScript-enabled RichEditor WebView or stored.
 * Only the markup the editor itself produces is kept (formatting, headings, lists, alignment,
 * text colour and to-do checkboxes); scripts, event handlers, links, images and anything that
 * could load remote content are removed.
 */
public final class NoteSanitizer {
    /** The only inline handler the editor writes: RichEditor's insertTodo(). */
    private static final Pattern TODO_ONCLICK = Pattern.compile("RE\\.setTodo\\('[A-Za-z0-9_]*'\\)");

    private static final Safelist SAFELIST = new Safelist()
            .addTags("b", "strong", "i", "em", "u", "strike", "s", "sub", "sup",
                    "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "span", "br",
                    "ul", "ol", "li", "blockquote", "font", "input")
            .addAttributes(":all", "style", "align")
            .addAttributes("font", "color")
            .addAttributes("input", "type", "name", "value", "checked", "class", "onclick");

    private NoteSanitizer() {
    }

    public static String clean(String html) {
        if (html == null) {
            return "";
        }
        Document clean = new Cleaner(SAFELIST).clean(Jsoup.parseBodyFragment(html));
        clean.outputSettings().prettyPrint(false);
        for (Element element : clean.body().getAllElements()) {
            String style = element.attr("style").toLowerCase(Locale.ROOT);
            // CSS cannot run script in a WebView, but url() can fetch remote content.
            if (style.contains("url") || style.contains("expression") || style.contains("@") || style.contains("\\")) {
                element.removeAttr("style");
            }
            if (element.tagName().equals("input")) {
                if (!element.attr("type").equalsIgnoreCase("checkbox")) {
                    element.remove();
                    continue;
                }
                if (!TODO_ONCLICK.matcher(element.attr("onclick")).matches()) {
                    element.removeAttr("onclick");
                }
            }
        }
        return clean.body().html();
    }
}
