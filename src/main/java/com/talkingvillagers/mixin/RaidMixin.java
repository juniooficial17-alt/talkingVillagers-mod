package com.talkingvillagers.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.talkingvillagers.settlement.Settlements;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;

/**
 * Notices when a raid concludes, which is how a settlement learns whether it was raided.
 *
 * <p>There is no vanilla event for this, and Fabric API 0.156.0+26.2 ships none either — the
 * status flip from ongoing to won or lost happens inline inside {@code Raid.tick}, so that is
 * the one place worth hooking. Injected at {@code TAIL} rather than gating on a written field,
 * since {@code isOver()}, {@code isVictory()} and {@code getCenter()} are all public and enough
 * on their own.
 *
 * <p>A raid lingers for up to 600 ticks after concluding, purely for its boss-bar animation, so
 * {@link #talkingvillagers$reported} guards against reporting the same outcome on every one of those
 * ticks.
 */
@Mixin(Raid.class)
public abstract class RaidMixin {
	@Unique
	private boolean talkingvillagers$reported;

	@Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("TAIL"))
	private void talkingvillagers$onTick(ServerLevel level, CallbackInfo ci) {
		Raid self = (Raid) (Object) this;
		if (this.talkingvillagers$reported || !self.isOver()) {
			return;
		}
		this.talkingvillagers$reported = true;
		Settlements.onRaidConcluded(level, self.getCenter(), self.isVictory());
	}
}
