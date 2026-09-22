package com.bebebe.agent.tts;

import java.util.regex.Pattern;

public final class SpeechText {

    private static final Pattern CODE_BLOCK = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern MARKUP = Pattern.compile("[*_#>|~]+");
    private static final Pattern BULLET = Pattern.compile("(?m)^\\s*[-•]\\s+");
    private static final Pattern EMOJI = Pattern.compile("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}]");
    private static final Pattern SPACES = Pattern.compile("[ \\t]+");
    private static final Pattern BLANKS = Pattern.compile("\\n{2,}");

    private SpeechText() {
    }

    public static String prepare(String text, int maxChars) {
        String t = CODE_BLOCK.matcher(text).replaceAll(" (фрагмент кода пропущен) ");
        t = INLINE_CODE.matcher(t).replaceAll("$1");
        t = URL.matcher(t).replaceAll("ссылка");
        t = BULLET.matcher(t).replaceAll("");
        t = MARKUP.matcher(t).replaceAll("");
        t = EMOJI.matcher(t).replaceAll("");
        t = SPACES.matcher(t).replaceAll(" ");
        t = BLANKS.matcher(t).replaceAll("\n");
        t = t.strip();
        if (t.length() <= maxChars) {
            return t;
        }
        String head = t.substring(0, maxChars);
        int cut = Math.max(head.lastIndexOf(". "), Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        if (cut < maxChars / 2) {
            cut = head.lastIndexOf(' ');
        }
        return (cut > 0 ? head.substring(0, cut + 1) : head).strip() + " Дальше — в тексте.";
    }
}
