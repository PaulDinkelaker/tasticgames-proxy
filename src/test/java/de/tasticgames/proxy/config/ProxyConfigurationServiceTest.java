package de.tasticgames.proxy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyConfigurationServiceTest {

    @Test
    void createsDefaultsAndStableInstanceId(@TempDir Path dir) throws Exception {
        ProxyConfigurationService service = new ProxyConfigurationService(dir, LoggerFactory.getLogger("test"), name -> null);
        service.start();
        ProxyConfiguration configuration = service.configuration();
        assertTrue(Files.exists(dir.resolve("config.properties")));
        assertTrue(configuration.identity().proxyId().startsWith("proxy-"));
        assertEquals("EU_FRANKFURT", configuration.identity().region());
        assertEquals("alpha", configuration.identity().environment());
        assertFalse(configuration.api().credentialsConfigured(), "CHANGE_ME must not count as configured");
        assertEquals(1, configuration.servers().size());
        assertEquals("LOBBY", configuration.servers().get("lobby").type());
        service.stop();

        // second start keeps the generated instance id
        ProxyConfigurationService again = new ProxyConfigurationService(dir, LoggerFactory.getLogger("test"), name -> null);
        again.start();
        assertEquals(configuration.identity().proxyId(), again.configuration().identity().proxyId());
    }

    @Test
    void environmentOverridesWinOverFile(@TempDir Path dir) throws Exception {
        Map<String, String> env = Map.of(
                "TASTIC_API_KEY", "secret-key",
                "TASTIC_API_BASE_URL", "http://localhost:8080/",
                "TASTIC_PROXY_ID", "Proxy-EU-01",
                "TASTIC_PROXY_REGION", "in-mumbai");
        Function<String, String> lookup = env::get;
        ProxyConfigurationService service = new ProxyConfigurationService(dir, LoggerFactory.getLogger("test"), lookup);
        service.start();
        ProxyConfiguration configuration = service.configuration();
        assertEquals("proxy-eu-01", configuration.identity().proxyId());
        assertEquals("IN_MUMBAI", configuration.identity().region());
        assertEquals("secret-key", configuration.api().apiKey());
        assertTrue(configuration.api().credentialsConfigured());
        // secrets must never be written back to the file
        String file = Files.readString(dir.resolve("config.properties"));
        assertFalse(file.contains("secret-key"));
    }

    @Test
    void invalidValuesFailFast(@TempDir Path dir) throws Exception {
        ProxyConfigurationService service = new ProxyConfigurationService(dir, LoggerFactory.getLogger("test"), name -> null);
        service.start();
        service.stop();
        Path file = dir.resolve("config.properties");
        String content = Files.readString(file).replace("health.failure-threshold=3", "health.failure-threshold=0");
        Files.writeString(file, content);
        ProxyConfigurationService broken = new ProxyConfigurationService(dir, LoggerFactory.getLogger("test"), name -> null);
        assertThrows(IllegalArgumentException.class, broken::start);
    }

    @Test
    void reloadOnlySwapsReloadableSections(@TempDir Path dir) throws Exception {
        ProxyConfigurationService service = new ProxyConfigurationService(dir, LoggerFactory.getLogger("test"), name -> null);
        service.start();
        String originalId = service.configuration().identity().proxyId();
        Path file = dir.resolve("config.properties");
        String content = Files.readString(file)
                .replace("routing.capacity-safety-margin=2", "routing.capacity-safety-margin=5")
                .replace("proxy.instance-id=" + originalId, "proxy.instance-id=other-proxy");
        Files.writeString(file, content);
        List<String> changed = service.reload();
        assertTrue(changed.contains("routing"));
        assertEquals(5, service.configuration().routing().capacitySafetyMargin());
        assertEquals(originalId, service.configuration().identity().proxyId(), "identity is not reloadable");
        assertEquals(Duration.ofSeconds(15), service.configuration().routing().transferTimeout());
    }

    @Test
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ProxyConfiguration.Identity("bad id!", "n", "EU", "alpha", "", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ProxyConfiguration.Identity("ok", "n", "eu-frankfurt!", "alpha", "", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ProxyConfiguration.Social(Duration.ZERO, 1, 1, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 2, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, true));
        assertEquals("EU_FRANKFURT", new ProxyConfiguration.ServerDefinition("Lobby-1", "lobby", "eu-frankfurt", 10, 10, null).region());
    }
}
