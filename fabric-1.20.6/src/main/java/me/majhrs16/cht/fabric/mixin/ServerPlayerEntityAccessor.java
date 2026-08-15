package me.majhrs16.cht.fabric.mixin;

import net.minecraft.server.network.ServerPlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the client's locale string stored in the {@code language} field of
 * {@link ServerPlayerEntity}, which has no public getter in 1.20.6.
 */
@Mixin(ServerPlayerEntity.class)
public interface ServerPlayerEntityAccessor {

    @Accessor("language")
    String getLanguage();
}