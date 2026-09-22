package com.bebebe.agent.tools.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDuckGoProviderTest {

    private static final String HTML = """
            <div class="result results_links results_links_deep web-result ">
              <h2 class="result__title">
                <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fbankiros.ru%2Fcurrency%2Fusd&amp;rut=abc">Курс доллара на <b>сегодня</b></a>
              </h2>
              <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fbankiros.ru%2Fcurrency%2Fusd&amp;rut=abc">Котировки <b>USD/RUB</b> в реальном времени</a>
            </div>
            <div class="result">
              <h2 class="result__title"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.vbr.ru%2Fusd&amp;rut=def">ЦБ РФ</a></h2>
              <a class="result__snippet" href="#">Официальный курс</a>
            </div>
            """;

    @Test
    void parsesLinksAndSnippets() {
        List<SearchResult> results = DuckDuckGoProvider.parse(HTML, 10);

        assertEquals(2, results.size());
        assertEquals("Курс доллара на сегодня", results.get(0).title());
        assertEquals("https://bankiros.ru/currency/usd", results.get(0).url(), "the address must be unpacked from uddg");
        assertEquals("Котировки USD/RUB в реальном времени", results.get(0).snippet());
        assertEquals("https://www.vbr.ru/usd", results.get(1).url());
    }

    @Test
    void respectsLimit() {
        assertEquals(1, DuckDuckGoProvider.parse(HTML, 1).size());
    }

    @Test
    void unpacksRedirect() {
        assertEquals("https://example.com/a?b=1",
                DuckDuckGoProvider.unwrap("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&rut=x"));
        assertEquals("https://direct.example", DuckDuckGoProvider.unwrap("https://direct.example"));
        assertEquals("https://proto.less", DuckDuckGoProvider.unwrap("//proto.less"));
    }

    @Test
    void emptyPageGivesNothing() {
        assertTrue(DuckDuckGoProvider.parse("<html></html>", 5).isEmpty());
    }

    @Test
    void braveParsesJson() {
        List<SearchResult> results = BraveSearchProvider.parse("""
                {"web":{"results":[
                  {"title":"A","url":"https://a","description":"<b>desc</b> a"},
                  {"title":"B","url":"https://b","description":"desc b"}]}}""", 5);

        assertEquals(2, results.size());
        assertEquals("desc a", results.get(0).snippet());
    }
}
