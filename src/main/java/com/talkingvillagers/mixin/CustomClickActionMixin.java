package com.talkingvillagers.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.talkingvillagers.ui.CustomClicks;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Delivers a tap on a book control to the mod.
 *
 * <p>Vanilla routes this packet to {@code MinecraftServer.handleCustomClickAction}, which is given
 * the action id and payload but not the player who clicked — so the hook has to go here, on the
 * connection, where the player is known.
 *
 * <p>Injected at {@code RETURN} rather than {@code HEAD} deliberately. The first thing the vanilla
 * method does is {@code PacketUtils.ensureRunningOnSameThread}, which is still running on the
 * netty thread and throws to reschedule itself onto the server thread. Injecting at the head would
 * therefore run the mod's world-touching code off-thread; by {@code RETURN} the guard has passed
 * and this is the server thread.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class CustomClickActionMixin {
	/**
	 * Hands the action to {@link CustomClicks}, ignoring anything that is not this mod's.
	 *
	 * <p>The listener is only a {@code ServerGamePacketListenerImpl} once the player is in a world.
	 * The same packet can arrive during configuration, where there is no player to act for and
	 * nothing the mod wants.
	 */
	@Inject(method = "handleCustomClickAction", at = @At("RETURN"))
	private void talkingvillagers$dispatchBookAction(
		ServerboundCustomClickActionPacket packet, CallbackInfo info
	) {
		if ((Object) this instanceof ServerGamePacketListenerImpl listener) {
			CustomClicks.dispatch(listener.getPlayer(), packet.id(), packet.payload());
		}
	}
}
