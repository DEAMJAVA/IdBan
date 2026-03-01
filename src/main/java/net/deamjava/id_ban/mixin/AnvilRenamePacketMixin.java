package net.deamjava.id_ban.mixin;

import net.deamjava.id_ban.detection.AnvilProbeManager;
import net.minecraft.network.packet.c2s.play.RenameItemC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the RenameItemC2SPacket (sent by the client when renaming an item in an anvil).
 *
 * When the server sends an anvil screen with a translation-key item name, the client
 * resolves the key into its localised string and sends it back as the rename text.
 * We capture that string and hand it to {@link AnvilProbeManager} before vanilla
 * processing occurs.
 *
 * Written in Java because Mixin + Kotlin can have subtle issues with @Shadow fields.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class AnvilRenamePacketMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(
            method = "onRenameItem(Lnet/minecraft/network/packet/c2s/play/RenameItemC2SPacket;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void idBan$onRenameItem(RenameItemC2SPacket packet, CallbackInfo ci) {
        if (player == null) return;

        String name = packet.getName();
        // Forward to the probe manager; if it was a probe response, cancel vanilla handling
        boolean wasProbe = AnvilProbeManager.INSTANCE.onProbeResponse(player, name);
        if (wasProbe) {
            ci.cancel();
        }
    }
}