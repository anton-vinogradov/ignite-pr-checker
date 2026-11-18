package com.github.igniteprchecker.web;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ProjectPagesController {
    @GetMapping("/")
    public ResponseEntity<Void> home() {
        return ResponseEntity.status(302).location(URI.create("/ignite")).build();
    }

    @GetMapping({"/ignite", "/python", "/php", "/nodejs", "/extensions", "/settings"})
    public String project() {
        return "forward:/index.html";
    }

    /** The master board's address before the page was renamed: bookmarks and shared links still name it. */
    @GetMapping("/flaky.html")
    public ResponseEntity<Void> masterBoardOldAddress() {
        return ResponseEntity.status(301).location(URI.create("/master.html")).build();
    }
}
