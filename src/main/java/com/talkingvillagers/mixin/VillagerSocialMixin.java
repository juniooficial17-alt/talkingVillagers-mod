package com.talkingvillagers.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.talkingvillagers.social.Relationships;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;

/**
 * Hooks the mod's relationship system into vanilla villager behaviour.
 *
 * <p>Everything here piggybacks on machinery Minecraft already runs, rather than duplicating
 * it: vanilla's reputation events already fan out to witnesses, and vanilla's trade pricing
 * already reads a single reputation number, so the mod only has to observe the first and
 * contribute to the second.
 */
@Mixin(Villager.class)
public abstract class VillagerSocialMixin {
	/**
	 * Mirrors every vanilla reputation event into the mod's own meter.
	 *
	 * <p>This one hook covers being hit, being traded with, watching a neighbour be murdered,
	 * being cured of zombification, and losing the village golem — for players and for other
	 * villagers alike.
	 */
	@Inject(method = "onReputationEventFrom", at = @At("HEAD"))
	private void talkingvillagers$recordReputationEvent(
		ReputationEventType type, Entity source, CallbackInfo info
	) {
		Relationships.onVanillaReputationEvent((Villager) (Object) this, type, source);
	}

	/**
	 * Blends the mod's relationship meter into the reputation figure vanilla uses to set trade
	 * prices, so being liked makes trading cheaper through the ordinary vanilla path.
	 */
	@Inject(method = "getPlayerReputation", at = @At("RETURN"), cancellable = true)
	private void talkingvillagers$blendReputationIntoPrices(
		Player player, CallbackInfoReturnable<Integer> returnable
	) {
		int offset = Relationships.priceReputationOffset((Villager) (Object) this, player);
		if (offset != 0) {
			returnable.setReturnValue(returnable.getReturnValue() + offset);
		}
	}

	/**
	 * A villager driven to permanent hatred refuses to open a trade at all.
	 *
	 * <p>Cancelled at the head of {@code mobInteract}, so the trade screen never opens. The
	 * angry particles are the only feedback a vanilla client can be given here, and they read
	 * clearly enough as a refusal.
	 */
	@Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
	private void talkingvillagers$refuseTradeWhenHated(
		Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> returnable
	) {
		Villager self = (Villager) (Object) this;
		if (!Relationships.refusesToTrade(self, player)) {
			return;
		}

		if (self.level() instanceof ServerLevel level) {
			level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
				self.getX(), self.getEyeY() + 0.5, self.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
		}
		returnable.setReturnValue(InteractionResult.SUCCESS_SERVER);
	}

	/**
	 * Treats an item a player threw to a villager as a gift.
	 *
	 * <p>Injected at the head, while the item stack is still intact — by the time the pickup
	 * returns, the stack may have been consumed into the villager's inventory. Vanilla only
	 * calls this for items the villager actually wants, so no filtering is needed here.
	 */
	@Inject(method = "pickUpItem", at = @At("HEAD"))
	private void talkingvillagers$noteGift(ServerLevel level, ItemEntity item, CallbackInfo info) {
		if (item.getOwner() instanceof Player giver) {
			Relationships.onGiftReceived((Villager) (Object) this, giver, item.getItem().copy());
		}
	}
}
