package com.talkingvillagers.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Disables vanilla villager breeding outright.
 *
 * <p>In this mod children come only from married couples, through {@code Births} — so the
 * vanilla "two well-fed villagers near a spare bed produce a baby" behaviour has to go, or
 * villages would grow by a route that bypasses the whole family system.
 *
 * <p>Cancelling at the entry condition is the least invasive point available. The behaviour is
 * still registered and still ticks its gate, it just never starts, so no assumptions elsewhere
 * in the brain's behaviour list are disturbed. {@code Behavior.tryStart} is {@code final} and
 * calls this, making it the effective gate.
 */
@Mixin(VillagerMakeLove.class)
public abstract class VillagerMakeLoveMixin {
	// The full descriptor is spelled out because generic erasure leaves a synthetic bridge
	// overload taking LivingEntity, and this pins the injection to the real method.
	@Inject(
		method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;"
			+ "Lnet/minecraft/world/entity/npc/villager/Villager;)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void talkingvillagers$blockVanillaBreeding(
		ServerLevel level, Villager body, CallbackInfoReturnable<Boolean> returnable
	) {
		returnable.setReturnValue(false);
	}
}
