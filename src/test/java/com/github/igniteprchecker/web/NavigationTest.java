package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The search box vanished after the first PR was opened, the title was not a link, Back to the start
 * address did nothing, and the PR list items could not be opened in a new tab or reached from the
 * keyboard. Fifty open PRs cover a month of activity, so a filter over them plus "open #N" is enough.
 */
class NavigationTest {
    @Test
    void listEntriesAreRealLinks() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('');
            report({ list: page.el('prList').innerHTML });
            """);

        assertThat(out.get("list").asText()).contains("<a class=\"pr-link\" href=\"?pr=13575\"", "href=\"?pr=13461\"");
    }

    @Test
    void filterMatchesNumberOrTitleAndOpensAnyNumber() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13461');
            const match = q => page.run('prMatches')(page.run('allPrs'), q);
            page.el('prFilter').value = '#13999';
            page.el('prFilter').listeners.input[0]();
            report({
                byTitle: match('junit'),
                byNumber: match('13575'),
                elsewhere: match('#13999'),
                nothing: match('flaky'),
                offered: !page.el('prOpenAny').classList.contains('hidden') && page.el('prOpenAny').textContent,
            });
            """);

        assertThat(out.get("byTitle").toString()).isEqualTo("{\"nums\":[13575],\"open\":null,\"first\":13575}");
        assertThat(out.get("byNumber").get("first").asInt()).isEqualTo(13575);
        assertThat(out.get("elsewhere").get("first").asInt()).isEqualTo(13999);
        assertThat(out.get("nothing").get("first").isNull()).isTrue();
        assertThat(out.get("offered").asText()).isEqualTo("Open PR #13999 →");
    }

    @Test
    void enterInTheFilterOpensThatPrWhilePrIsOpen() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.el('prFilter').value = '13999';
            page.el('prFilter').listeners.keydown[0]({ key: 'Enter' });
            await page.settle();
            report({ selected: page.run('selectedPr'), pushed: page.pushed, filter: page.el('prFilter').value,
                analysed: page.fetches('/api/analyze?pr=13999').length });
            """);

        assertThat(out.get("selected").asInt()).isEqualTo(13999);
        assertThat(out.get("pushed").toString()).isEqualTo("[\"?pr=13999\"]");
        assertThat(out.get("filter").asText()).isEmpty();
        assertThat(out.get("analysed").asInt()).isEqualTo(1);
    }

    @Test
    void backToTheStartAddressShowsTheStartPage() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.location.search = '';
            await page.popstate();
            report({
                selected: page.run('selectedPr'),
                title: page.el('prTitle').textContent,
                resultsHidden: page.el('results').classList.contains('hidden'),
                actionsHidden: page.el('actions').classList.contains('hidden'),
                polls: page.timers().filter(t => !t.repeat).length,
            });
            """);

        assertThat(out.get("selected").isNull()).isTrue();
        assertThat(out.get("title").asText()).startsWith("Pick a PR");
        assertThat(out.get("resultsHidden").asBoolean()).isTrue();
        assertThat(out.get("actionsHidden").asBoolean()).isTrue();
        assertThat(out.get("polls").asInt()).isZero();
    }

    @Test
    void titleLeadsBackToTheStartPage() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            let prevented = false;
            page.el('brand').listeners.click[0]({ button: 0, preventDefault() { prevented = true; } });
            await page.settle();
            report({ selected: page.run('selectedPr'), pushed: page.pushed, prevented });
            """);

        assertThat(out.get("selected").isNull()).isTrue();
        assertThat(out.get("pushed").toString()).isEqualTo("[\"/\"]");
        assertThat(out.get("prevented").asBoolean()).isTrue();
    }

    @Test
    void slashJumpsToTheFilterAndOpensACollapsedList() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.run('localStorage').setItem('prPaneCollapsed', '1');
            await page.load('?pr=13575');
            const collapsed = page.el('analyzeView').classList.contains('collapsed');
            await page.keydown('/');
            report({ collapsed, focused: page.run('document').activeElement === page.el('prFilter'),
                stillCollapsed: page.el('analyzeView').classList.contains('collapsed') });
            """);

        assertThat(out.get("collapsed").asBoolean()).isTrue();
        assertThat(out.get("focused").asBoolean()).isTrue();
        assertThat(out.get("stillCollapsed").asBoolean()).isFalse();
    }
}
