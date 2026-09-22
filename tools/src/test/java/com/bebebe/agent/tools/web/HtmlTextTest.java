package com.bebebe.agent.tools.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlTextTest {

    @Test
    void removesScriptsStylesAndNavigation() {
        String html = """
                <html><head><style>body{}</style><script>alert(1)</script></head>
                <body><nav>Меню</nav><h1>Заголовок</h1><p>Абзац &laquo;текста&raquo; &mdash; и всё.</p>
                <footer>подвал</footer></body></html>""";

        String text = HtmlText.extract(html);

        assertTrue(text.contains("Заголовок"));
        assertTrue(text.contains("Абзац «текста» — и всё."));
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("Меню"));
        assertFalse(text.contains("подвал"));
        assertFalse(text.contains("<"));
    }

    @Test
    void blockTagsBecomeLineBreaks() {
        String text = HtmlText.extract("<p>раз</p><p>два</p><br>три");

        assertTrue(text.contains("раз\n"), text);
        assertTrue(text.contains("два"));
    }

    @Test
    void numericEntitiesAreDecoded() {
        assertEquals("рубль", HtmlText.unescape("&#1088;&#1091;&#1073;&#1083;&#1100;"));
        assertEquals("а", HtmlText.unescape("&#x430;"));
        assertEquals("&#zzz;", HtmlText.unescape("&#zzz;"));
    }

    @Test
    void snippetIsStrippedOfTagsAndSpaces() {
        assertEquals("Курс доллара 95,3 ₽", HtmlText.strip("  <b>Курс</b>  доллара\n 95,3 &#8381; "));
    }
}
