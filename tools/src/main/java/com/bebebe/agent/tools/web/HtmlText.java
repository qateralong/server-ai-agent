package com.bebebe.agent.tools.web;

import java.util.regex.Pattern;

public final class HtmlText {

    private static final Pattern DROP_BLOCKS = Pattern.compile(
            "(?is)<(script|style|noscript|svg|nav|header|footer|aside|form|iframe)\\b[^>]*>.*?</\\1>");
    private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern BLOCK_TAGS = Pattern.compile(
            "(?i)</?(p|div|br|li|ul|ol|h[1-6]|tr|td|th|table|section|article|blockquote|pre)\\b[^>]*>");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n\\s*\\n+");

    private HtmlText() {
    }

    public static String extract(String html) {
        if (html == null) {
            return "";
        }
        String text = COMMENTS.matcher(html).replaceAll("");
        text = DROP_BLOCKS.matcher(text).replaceAll(" ");
        text = BLOCK_TAGS.matcher(text).replaceAll("\n");
        text = TAGS.matcher(text).replaceAll(" ");
        text = unescape(text);
        text = SPACES.matcher(text).replaceAll(" ");
        text = text.replaceAll("(?m)^[ ]+|[ ]+$", "");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    private static final Pattern ANY_SPACE = Pattern.compile("\\s+");

    public static String strip(String fragment) {
        if (fragment == null) {
            return "";
        }
        return ANY_SPACE.matcher(unescape(TAGS.matcher(fragment).replaceAll(" "))).replaceAll(" ").strip();
    }

    static String unescape(String text) {
        String out = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…");

        StringBuilder sb = new StringBuilder(out.length());
        java.util.regex.Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(out);
        while (m.find()) {
            try {
                int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(new String(Character.toChars(code))));
            } catch (RuntimeException e) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
