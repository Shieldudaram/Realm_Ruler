package com.Chris__.realm_ruler;

import com.Chris__.realm_ruler.ui.pages.ctf.CtfMainUiService;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;

public final class CtfCommand extends CommandBase {

    private static final Message MSG_USAGE = Message.raw("Usage: /ctf");
    private static final Message MSG_PLAYERS_ONLY = Message.raw("[RealmRuler] Players only.");
    private static final Message MSG_NOT_READY = Message.raw("[RealmRuler] CTF UI is not ready yet.");
    private static final Message MSG_UI_UNAVAILABLE = Message.raw("[RealmRuler] CTF UI is unavailable right now.");

    private final CtfMainUiService mainUiService;

    public CtfCommand(CtfMainUiService mainUiService) {
        super("ctf", "Opens the main Capture The Flag UI.");
        this.setAllowsExtraArguments(true);
        this.setPermissionGroup(GameMode.Adventure);
        this.mainUiService = mainUiService;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String[] args = splitArgs(ctx.getInputString());
        if (args.length > 1) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        if (ctx.sender() == null || ctx.sender().getUuid() == null) {
            ctx.sendMessage(MSG_PLAYERS_ONLY);
            return;
        }

        String uuid = ctx.sender().getUuid().toString();
        if (uuid == null || uuid.isBlank()) {
            ctx.sendMessage(MSG_PLAYERS_ONLY);
            return;
        }

        if (mainUiService == null) {
            ctx.sendMessage(MSG_NOT_READY);
            return;
        }

        if (!mainUiService.isUiAvailable()) {
            ctx.sendMessage(MSG_UI_UNAVAILABLE);
            return;
        }

        mainUiService.requestOpen(uuid);
        ctx.sendMessage(Message.raw("[RealmRuler] Opening Capture The Flag UI..."));
    }

    private static String[] splitArgs(String raw) {
        if (raw == null) return new String[0];
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return new String[0];
        return trimmed.split("\\s+");
    }
}
