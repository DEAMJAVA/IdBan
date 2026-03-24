package net.deamjava.id_ban.mixin;

import net.deamjava.id_ban.detection.CommandSnoopDetector;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts RequestCommandCompletionsC2SPacket (the tab-complete request sent by
 * the client when the player presses Tab in the chat box).
 *
 * We forward the partialCommand string to {@link CommandSnoopDetector} which checks
 * it against the configured list of client-side mod command prefixes.
 *
 * We do NOT cancel the packet — vanilla tab-complete still works normally.
 * This is purely a passive observation.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class TabCompletePacketMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(
            method = "onRequestCommandCompletions(Lnet/minecraft/network/packet/c2s/play/RequestCommandCompletionsC2SPacket;)V",
            at = @At("HEAD")
    )
    private void idBan$onTabComplete(RequestCommandCompletionsC2SPacket packet, CallbackInfo ci) {
        if (player == null) return;
        String partial = packet.getPartialCommand();
        if (partial == null || partial.isEmpty()) return;
        CommandSnoopDetector.INSTANCE.onTabComplete(player, partial);
    }
}