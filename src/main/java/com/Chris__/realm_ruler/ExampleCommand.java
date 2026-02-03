package com.Chris__.Realm_Ruler;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.Chris__.Realm_Ruler.targeting.TargetingService;

import javax.annotation.Nonnull;

public class ExampleCommand extends CommandBase {

    private final String pluginName;
    private final String pluginVersion;
    private final TargetingService targetingService;

    public ExampleCommand(String pluginName, String pluginVersion, TargetingService targetingService) {
        super("test", "Prints a test message from the " + pluginName + " plugin.");
        this.setPermissionGroup(GameMode.Adventure);
        this.pluginName = pluginName;
        this.pluginVersion = pluginVersion;
        this.targetingService = targetingService;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("Hello from the " + pluginName + " v" + pluginVersion + " plugin! V0.1.26"));

        // Start a shared 3-minute timer for everyone
        targetingService.queueTimerStart(60 * 15);

        ctx.sendMessage(Message.raw("Started match timer: 15:00"));
    }
}
