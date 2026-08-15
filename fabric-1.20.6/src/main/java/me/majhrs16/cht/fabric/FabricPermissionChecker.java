package me.majhrs16.cht.fabric;

import me.majhrs16.cht.core.platform.PermissionChecker;
import me.majhrs16.cht.core.player.Subject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Fabric {@link PermissionChecker}. Vanilla has no permission API, so only the
 * operator status is honored; console always passes.
 */
final class FabricPermissionChecker implements PermissionChecker {

    private final MinecraftServer server;

    FabricPermissionChecker(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean has(Subject subject, String node) {
        Object handle = subject.handle();
        if (handle instanceof ServerPlayerEntity) {
            return server.getPlayerManager().isOperator(
                ((ServerPlayerEntity) handle).getGameProfile());
        }
        return subject.kind() == Subject.SubjectKind.CONSOLE;
    }
}