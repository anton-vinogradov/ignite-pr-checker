package com.github.igniteprchecker.web;

import com.github.igniteprchecker.update.UpdateService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports the running vs. latest version and, for a logged-in user, applies an update. */
@RestController
@RequestMapping("/api")
public class UpdateController {
    private final UpdateService update;

    public UpdateController(UpdateService update) {
        this.update = update;
    }

    /** Public: current/latest version and whether an update is available. */
    @GetMapping("/version")
    public UpdateService.Status version() {
        return update.status();
    }

    /** Guarded: download the latest release and restart. Any logged-in user may trigger it. */
    @PostMapping("/update")
    public ResponseEntity<?> update() {
        try {
            update.performUpdate();

            return ResponseEntity.ok(Map.of("status", "updating"));
        }
        catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "update failed: " + e.getMessage()));
        }
    }
}
