package com.talkingvillagers.data;

import com.mojang.serialization.Codec;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.social.VillagerSoul;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

/**
 * Registration of the mod's data attachments.
 */
public final class Attachments {
	/**
	 * Everything the mod knows about a villager. Persistent, so it is written into the
	 * villager's own entity data and survives a reload with no bookkeeping of our own.
	 *
	 * <p>Deliberately not {@code copyOnDeath}: a dead villager's soul should not follow
	 * anything else around, and grief is carried by the survivors' souls instead.
	 */
	public static final AttachmentType<VillagerSoul> SOUL = AttachmentRegistry.create(
		TalkingVillagers.id("soul"),
		builder -> builder.persistent(VillagerSoul.CODEC)
	);

	/**
	 * Marks a Text Display entity as one of ours.
	 *
	 * <p>Speech bubbles are real vanilla entities, so an autosave or a crash mid-conversation
	 * can persist one into the save file, where it would hang in the air forever. Marking them
	 * lets the mod recognise its own leftovers on load and clear them out. The marker is
	 * applied *after* the entity is added to the world, so the load event that fires on spawn
	 * does not see it and immediately delete a bubble we just created.
	 */
	public static final AttachmentType<Boolean> SPEECH_BUBBLE = AttachmentRegistry.createPersistent(
		TalkingVillagers.id("speech_bubble"),
		Codec.BOOL
	);

	private Attachments() {
	}

	/**
	 * Forces class initialisation. Attachment types must be registered before any world loads,
	 * and a field on an otherwise-unreferenced class would not be initialised in time.
	 */
	public static void register() {
		if (SOUL == null || SPEECH_BUBBLE == null) {
			throw new IllegalStateException("Attachments failed to register");
		}
	}
}
