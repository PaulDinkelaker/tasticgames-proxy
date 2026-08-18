package de.tasticgames.proxy.bus;

/** Command type constants of the network command bus. */
public final class CommandTypes {

    public static final String MAINTENANCE_CHANGED = "maintenance.changed";
    public static final String ALPHA_CHANGED = "alpha.changed";
    public static final String SERVER_STATE_CHANGED = "server.state_changed";
    public static final String PLAYER_MESSAGE = "player.message";
    public static final String PLAYER_TRANSFER = "player.transfer";
    public static final String PARTY_TRANSFER = "party.transfer";
    public static final String PARTY_TRANSFER_MEMBER = "party.transfer_member";
    public static final String PARTY_TRANSFER_MEMBER_RESULT = "party.transfer_member_result";
    public static final String PLAYER_KICK = "player.kick";
    public static final String PASS_LEVEL_UP = "pass.level_up";

    private CommandTypes() {
    }
}
