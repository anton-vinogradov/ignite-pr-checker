package com.github.igniteprchecker.github;

import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.ThinClientProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ThinClients {
    private final List<ClientSpec> clients;

    public ThinClients(PythonClientProperties python, ThinClientProperties others) {
        clients = List.of(new ClientSpec("python", "Python", python.repo(), python.suite(), python.igniteVcsRoot(),
                python.clientVcsRoot()),
            spec("php", "PHP", others.php(), "IgniteTests24Java8_ThinClientPhp", "GitHubApacheIgnitePhpThinClient"),
            spec("nodejs", "Node.js", others.nodejs(), "IgniteTests24Java8_ThinClientNodeJs",
                "GitHubApacheIgniteNodejsThinClient"));
    }

    public List<ClientSpec> all() {
        return clients;
    }

    public static ThinClients defaults() {
        return new ThinClients(new PythonClientProperties(null, null, null, null),
            new ThinClientProperties(null, null));
    }

    public List<Project> catalog() {
        return clients.stream().map(Project::of).toList();
    }

    public record Project(String key, String label, String repo, String suite) {
        public static Project of(ClientSpec client) {
            return new Project(client.key(), client.label(), client.repo(), client.suiteLabel());
        }
    }

    public ClientSpec require(String key) {
        return clients.stream().filter(c -> c.key().equals(key)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown client project"));
    }

    public Optional<ClientSpec> byRepo(String repo) {
        return clients.stream().filter(c -> c.repo().equals(repo)).findFirst();
    }

    private static ClientSpec spec(String key, String label, ThinClientProperties.Settings settings, String suite,
        String root) {
        return new ClientSpec(key, label, value(settings == null ? null : settings.repo(),
                "apache/ignite-" + key + "-thin-client"),
            value(settings == null ? null : settings.suite(), suite),
            value(settings == null ? null : settings.igniteVcsRoot(), "GitHubApacheIgnite"),
            value(settings == null ? null : settings.clientVcsRoot(), root));
    }

    private static String value(String configured, String standard) {
        return configured == null || configured.isBlank() ? standard : configured.strip();
    }
}
