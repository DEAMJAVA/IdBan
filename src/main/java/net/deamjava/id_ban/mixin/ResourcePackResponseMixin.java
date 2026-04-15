package net.deamjava.id_ban.mixin;

import net.deamjava.id_ban.IdBan;
import net.deamjava.id_ban.detection.ModDetectionManager;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ResourcePackResponseMixin {

    @Inject(
            method = "handleResourcePackResponse",
            at = @At("TAIL")
    )
    private void idBan$onResourcePackResponse(
            ServerboundResourcePackPacket packet,
            CallbackInfo ci
    ) {
        if (!((Object) this instanceof ServerGamePacketListenerImpl gameListener)) return;
        ServerPlayer player = gameListener.player;
        if (player == null) return;

        ServerboundResourcePackPacket.Action action = packet.action();

        switch (action) {
            case ACCEPTED:
            case DECLINED:
            case FAILED_DOWNLOAD:
            case INVALID_URL:
                ModDetectionManager.INSTANCE.onResourcePackResponseReceived(player);
                break;
            default:
                break;
        }
    }
}