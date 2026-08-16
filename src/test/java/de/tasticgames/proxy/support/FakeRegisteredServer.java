package de.tasticgames.proxy.support;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal RegisteredServer stub for routing/health unit tests.
 */
public final class FakeRegisteredServer implements RegisteredServer {

    private final ServerInfo info;
    private final List<Player> players = new ArrayList<>();
    private volatile CompletableFuture<ServerPing> pingResult = CompletableFuture.failedFuture(new IllegalStateException("no ping configured"));

    public FakeRegisteredServer(String name) {
        this.info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    }

    public FakeRegisteredServer withPing(CompletableFuture<ServerPing> result) {
        this.pingResult = result;
        return this;
    }

    @Override
    public ServerInfo getServerInfo() {
        return info;
    }

    @Override
    public Collection<Player> getPlayersConnected() {
        return players;
    }

    @Override
    public CompletableFuture<ServerPing> ping() {
        return pingResult;
    }

    @Override
    public CompletableFuture<ServerPing> ping(PingOptions pingOptions) {
        return pingResult;
    }

    @Override
    public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
        return false;
    }

    @Override
    public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder encoder) {
        return false;
    }
}
