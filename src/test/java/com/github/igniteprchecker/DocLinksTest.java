package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The pictures in docs/img still showed "Update to v0.1.43", a j/k key hint the page never had and a P-F-P-F blocker
 * today's rule can't produce, and a picture no doc shows can't even be noticed as stale. Every picture in docs/img is
 * now shown by a doc and exists, and the READMEs' links into the tours and into themselves land on a heading.
 */
class DocLinksTest {
    private static final Path IMG = Path.of("docs/img");

    private static final Map<Path, Path> README_TO_TOUR = Map.of(
        Path.of("README.md"), Path.of("docs/features.md"),
        Path.of("README.ru.md"), Path.of("docs/features.ru.md"));

    private static final List<Path> DOCS = List.of(Path.of("README.md"), Path.of("README.ru.md"),
        Path.of("docs/features.md"), Path.of("docs/features.ru.md"));

    private static final Pattern IMAGE = Pattern.compile("!\\[[^]]*]\\(([^)\\s]+)\\)");

    private static final Pattern LINK = Pattern.compile("]\\(([^)\\s]*)#([^)\\s]+)\\)");

    @Test
    void everyPictureADocShowsExists() throws IOException {
        for (Path doc : DOCS) {
            Matcher image = IMAGE.matcher(read(doc));
            while (image.find())
                assertThat(doc.resolveSibling(image.group(1))).as(doc + " shows " + image.group(1)).isRegularFile();
        }
    }

    @Test
    void everyPictureInDocsImgIsShown() throws IOException {
        Set<Path> shown = new HashSet<>();
        for (Path doc : DOCS) {
            Matcher image = IMAGE.matcher(read(doc));
            while (image.find())
                shown.add(doc.resolveSibling(image.group(1)).normalize());
        }

        try (Stream<Path> files = Files.list(IMG)) {
            assertThat(files.map(Path::normalize)).as("pictures no doc shows").allMatch(shown::contains);
        }
    }

    @Test
    void theReadmeLinksLandOnHeadings() throws IOException {
        int links = 0;
        for (Map.Entry<Path, Path> pair : README_TO_TOUR.entrySet()) {
            Path readme = pair.getKey();
            Set<String> own = GlossaryTest.anchors(read(readme));
            Set<String> tour = GlossaryTest.anchors(read(pair.getValue()));

            Matcher link = LINK.matcher(read(readme));
            while (link.find()) {
                links++;
                String target = link.group(1);
                Set<String> anchors = target.isEmpty() ? own : tour;
                assertThat(target).as(readme + " links " + link.group()).isIn("", pair.getValue().toString());
                assertThat(anchors).as(readme + " links " + link.group()).contains(link.group(2));
            }
        }
        assertThat(links).as("links of both READMEs into the tours and into themselves").isGreaterThanOrEqualTo(10);
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, UTF_8);
    }
}
