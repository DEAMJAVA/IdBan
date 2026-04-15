package net.deamjava.id_ban.mixin;

import net.deamjava.id_ban.detection.AnvilProbeManager;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ServerGamePacketListenerImpl.class)
public abstract class AnvilRenamePacketMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(
            method = "handleRenameItem(Lnet/minecraft/network/protocol/game/ServerboundRenameItemPacket;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void idBan$onRenameItem(ServerboundRenameItemPacket packet, CallbackInfo ci) {
        if (player == null) return;

        String name = packet.getName();
        boolean wasProbe = AnvilProbeManager.INSTANCE.onProbeResponse(player, name);
        if (wasProbe) {
            ci.cancel();
        }
    }
}