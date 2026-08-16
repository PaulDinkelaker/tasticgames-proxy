package de.tasticgames.proxy.support;

import de.tasticgames.proxy.config.ProxyConfiguration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class TestConfigurations {

    private TestConfigurations() {
    }

    public static ProxyConfiguration.Routing routing() {
        return new ProxyConfiguration.Routing("EU_FRANKFURT", true, 2, "LOBBY", "LOBBY", Duration.ofSeconds(15),
                Duration.ofMillis(1500), Duration.ofSeconds(20), 100, 200, 1, false);
    }

    public static ProxyConfiguration.Routing routingNoCrossRegion() {
        return new ProxyConfiguration.Routing("EU_FRANKFURT", false, 0, "LOBBY", "LOBBY", Duration.ofSeconds(15),
                Duration.ofMillis(0), Duration.ofSeconds(20), 100, 200, 1, false);
    }

    public static ProxyConfiguration.Health health() {
        return new ProxyConfiguration.Health(Duration.ofSeconds(10), Duration.ofSeconds(3), 3, 2, Duration.ofSeconds(60), Duration.ofMillis(750));
    }

    public static ProxyConfiguration full() {
        return new ProxyConfiguration(
                new ProxyConfiguration.Identity("proxy-test", "Test", "EU_FRANKFURT", "test", "", Duration.ofSeconds(15)),
                new ProxyConfiguration.Api(false, "https://api.example/", "tastic-proxy", "CHANGE_ME", Duration.ofSeconds(5), Duration.ofSeconds(10), false),
                new ProxyConfiguration.Maintenance(false, false, Duration.ofSeconds(30)),
                new ProxyConfiguration.AlphaAccess(true, true, Duration.ofSeconds(60)),
                health(),
                routing(),
                new ProxyConfiguration.Presence(Duration.ofSeconds(60), Duration.ofSeconds(180), Duration.ofSeconds(120)),
                new ProxyConfiguration.Social(Duration.ofSeconds(10), 200, 8, Duration.ofSeconds(60), Duration.ofSeconds(300), 50,
                        Duration.ofSeconds(300), Duration.ofSeconds(60), 10, true),
                new ProxyConfiguration.Bus(Duration.ofMillis(1000), 50, Duration.ofSeconds(60)),
                new ProxyConfiguration.Telemetry(true, 5000, 200, Duration.ofSeconds(5), Duration.ofSeconds(60)),
                Map.of("lobby", new ProxyConfiguration.ServerDefinition("lobby", "LOBBY", "EU_FRANKFURT", 200, 100, List.of()))
        );
    }
}
