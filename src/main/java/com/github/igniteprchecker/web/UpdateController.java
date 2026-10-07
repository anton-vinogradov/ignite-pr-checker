package com.github.igniteprchecker.web;

import com.github.igniteprchecker.update.UpdateService;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports the running vs. latest version and, for the operator, restarts or updates the service. */
@RestController
@RequestMapping("/api")
public class UpdateController {
    private final UpdateService update;
    private final AdminActions admin;

    public UpdateController(UpdateService update, AdminActions admin) {
        this.update = update;
        this.admin = admin;
    }

    /** Public: current/latest version and whether an update is available. */
    @GetMapping("/version")
    public UpdateService.Status version() {
        return update.status();
    }

    /** Restarts the service (systemd relaunches the current jar); caches are snapshotted on exit. */
    @PostMapping("/restart")
    public ResponseEntity<?> restart(@RequestAttribute(AuthInterceptor.USER_ATTR) String user) {
        Optional<AdminActions.Refusal> refused = admin.refusal(user, AdminActions.Action.RESTART);
        if (refused.isPresent())
            return refused.get().response();

        admin.record(user, AdminActions.Action.RESTART);
        update.restart();

        return ResponseEntity.ok(Map.of("status", "restarting"));
    }

    /** Downloads the latest release on the next start and restarts into it. */
    @PostMapping("/update")
    public ResponseEntity<?> update(@RequestAttribute(AuthInterceptor.USER_ATTR) String user) {
        Optional<AdminActions.Refusal> refused = admin.refusal(user, AdminActions.Action.UPDATE);
        if (refused.isPresent())
            return refused.get().response();

        try {
            update.performUpdate();
            admin.record(user, AdminActions.Action.UPDATE);

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
