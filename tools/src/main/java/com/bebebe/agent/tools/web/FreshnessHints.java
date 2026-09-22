package com.bebebe.agent.tools.web;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class FreshnessHints {

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;

    private static final List<Pattern> TRIGGERS = List.of(
            Pattern.compile("\\b(сейчас|сегодня|сегодняшн|вчера|завтра|на этой неделе|в этом году|актуальн|последн(ие|яя|ий) новост|свеж)", FLAGS),
            Pattern.compile("\\b(курс|котировк|стоимост|цена|сколько стоит|биткоин|доллар|евро|рубл|акци)", FLAGS),
            Pattern.compile("\\b(погод|температур|дожд|снег|прогноз)", FLAGS),
            Pattern.compile("\\b(новост|что случилось|что произошло|что нового|результат матча|счёт матча|кто выиграл)", FLAGS),
            Pattern.compile("\\b(версия|вышел|вышла|релиз|обновлени|changelog)", FLAGS),
            Pattern.compile("\\b(расписани|когда открывается|часы работы|пробк)", FLAGS));

    private FreshnessHints() {
    }

    public static boolean looksTimeSensitive(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return TRIGGERS.stream().anyMatch(p -> p.matcher(lower).find());
    }

    public static String hintFor(String text) {
        return looksTimeSensitive(text)
                ? "\n\nHint: the question seems to need up-to-date data (rates, weather, news, "
                  + "\"now\"/\"today\"). If you cannot answer from knowledge with confidence, "
                  + "use tool_call web_search without waiting to be asked to google.\n"
                : "";
    }
}
