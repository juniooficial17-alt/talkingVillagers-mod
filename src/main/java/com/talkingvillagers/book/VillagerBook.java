package com.talkingvillagers.book;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.identity.VillagerIdentity;
import com.talkingvillagers.identity.VillagerSouls;
import com.talkingvillagers.settlement.Settlement;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.Bond;
import com.talkingvillagers.social.Relationship;
import com.talkingvillagers.social.VillagerSoul;
import com.talkingvillagers.ui.CustomClicks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/**
 * A villager's life, written by sneak-clicking them with a book.
 *
 * <p>Where the village record is a survey, this is a portrait: who they are, their family and
 * romantic status, and the company they keep, sorted into how they actually feel about each
 * person. It is also where the two pieces of village politics surface — the mayor's rename
 * control, and, on the sitting mayor's own page, the offer to take the office from them.
 */
public final class VillagerBook {
	private VillagerBook() {
	}

	/** Writes this villager's record into the book the player is holding. */
	public static void give(
		ServerPlayer player, Villager villager, ServerLevel level, InteractionHand hand
	) {
		VillagerSoul soul = VillagerSouls.ensureSoul(villager, level);
		Optional<Settlement> home = soul.settlement().flatMap(id -> Settlements.byId(id, level));

		List<Component> pages = new ArrayList<>();
		// The first page carries the controls, so it stays deliberately short: anything that
		// overflows a book page is not drawn at all, and a control nobody can see is no control.
		pages.add(summaryPage(villager, soul, home));
		pages.add(familyPage(soul, level));
		pages.addAll(Books.section("Best Friends", namesWithLabel(soul, level, Relationship.Label.BEST_FRIEND)));
		pages.addAll(Books.section("Friends", namesWithLabel(soul, level, Relationship.Label.FRIEND)));
		pages.addAll(Books.section("Acquaintance", namesWithLabel(soul, level, Relationship.Label.NEUTRAL)));
		pages.addAll(Books.section("Enemies", namesWithLabel(soul, level, Relationship.Label.ENEMY)));
		pages.addAll(Books.section("Sworn Enemies", namesWithLabel(soul, level, Relationship.Label.SWORN_ENEMY)));

		Books.exchange(player, hand, Books.write("Villager Autobiography", soul.identity().fullName(), pages));
	}

	/** Name, home, and the fixed facts a reader expects, plus whatever controls they've earned. */
	private static Component summaryPage(
		Villager villager, VillagerSoul soul, Optional<Settlement> home
	) {
		VillagerIdentity identity = soul.identity();
		MutableComponent page = Component.empty().append(bold(identity.fullName())).append("\n");
		home.ifPresent(settlement -> page.append("from " + settlement.name() + "\n"));

		page.append("\n")
			.append(capitalize(identity.genderIdentity().getSerializedName())
				+ (identity.transgender() ? " (transgender)" : "") + "\n")
			.append(describeSexuality(identity) + "\n")
			.append(describeProfession(villager) + "\n")
			.append("\n")
			.append(identity.mbti().name() + " - " + identity.mbti().flavour() + "\n");

		home.ifPresent(settlement -> appendControls(page, settlement, villager));
		return page;
	}

	/** Current romantic status — whichever bond applies, if any — and family by name. */
	private static Component familyPage(VillagerSoul soul, ServerLevel level) {
		String heading = "Partner";
		Optional<String> partnerName = Optional.empty();
		for (Bond bond : List.of(Bond.SPOUSE, Bond.ENGAGED, Bond.LOVER, Bond.WIDOWED)) {
			Optional<UUID> target = soul.withBond(bond).stream().findFirst();
			if (target.isPresent()) {
				heading = romanticHeading(bond);
				partnerName = soul.relationship(target.get()).flatMap(relationship -> nameOf(relationship, level));
				break;
			}
		}

		MutableComponent page = Component.empty()
			.append(bold(heading)).append("\n")
			.append(partnerName.orElse("None")).append("\n\n")
			.append(bold("Parents")).append("\n");
		appendNamesOrNone(page, namesOf(soul, level, Bond.PARENT));

		page.append("\n").append(bold("Children")).append("\n");
		appendNamesOrNone(page, namesOf(soul, level, Bond.CHILD));
		return page;
	}

	private static void appendNamesOrNone(MutableComponent page, List<String> names) {
		if (names.isEmpty()) {
			page.append("None\n");
			return;
		}
		for (String name : names) {
			page.append(name + "\n");
		}
	}

	private static String romanticHeading(Bond bond) {
		return switch (bond) {
			case SPOUSE -> "Spouse";
			case ENGAGED -> "Engaged";
			case LOVER -> "Lover";
			case WIDOWED -> "Ex-Spouse";
			default -> "Partner";
		};
	}

	private static MutableComponent bold(String text) {
		return Component.literal(text).withStyle(ChatFormatting.BOLD);
	}

	private static String describeSexuality(VillagerIdentity identity) {
		return identity.sexuality()
			.map(sexuality -> capitalize(sexuality.getSerializedName()))
			.orElse(identity.adult() ? "Heterosexual" : "Not yet known");
	}

	private static String capitalize(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	/**
	 * Offers to take over as mayor, but only on the sitting mayor's own record. Reading about an
	 * ordinary resident tells you nothing about who runs the place, so it should not offer you
	 * the job. Renaming the village is a Village Directory control, not one that belongs here.
	 */
	private static void appendControls(
		MutableComponent page, Settlement settlement, Villager villager
	) {
		if (settlement.mayorVillager().filter(villager.getUUID()::equals).isPresent()) {
			page.append("\n").append(Books.action("[Take over as mayor]",
				CustomClicks.VILLAGE_CLAIM, CustomClicks.settlement(settlement.id()),
				"Take charge of " + settlement.name()));
		}
	}

	private static String describeProfession(Villager villager) {
		var profession = villager.getVillagerData().profession();
		if (profession.is(VillagerProfession.NONE)) {
			return "none, and looking";
		}
		if (profession.is(VillagerProfession.NITWIT)) {
			return "nitwit";
		}
		return profession.unwrapKey()
			.map(key -> key.identifier().getPath().replace('_', ' '))
			.orElse("unknown");
	}

	/** Every relationship carrying this derived label, alphabetically. */
	private static List<String> namesWithLabel(VillagerSoul soul, ServerLevel level, Relationship.Label label) {
		return soul.relationships().values().stream()
			.filter(relationship -> relationship.label() == label)
			.map(relationship -> nameOf(relationship, level))
			.flatMap(Optional::stream)
			.sorted()
			.toList();
	}

	/** Names of everyone this villager holds {@code bond} with. */
	private static List<String> namesOf(VillagerSoul soul, ServerLevel level, Bond bond) {
		return soul.withBond(bond).stream()
			.map(id -> soul.relationship(id).flatMap(relationship -> nameOf(relationship, level)))
			.flatMap(Optional::stream)
			.toList();
	}

	/** A name for a relationship's target, whether villager, player, or a memorial. */
	private static Optional<String> nameOf(Relationship relationship, ServerLevel level) {
		UUID id = relationship.target();
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
		if (player != null) {
			return Optional.of(player.getName().getString());
		}
		if (level.getEntityInAnyDimension(id) instanceof Villager villager) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul != null) {
				return Optional.of(soul.identity().firstName());
			}
		}
		return relationship.memorialName().isEmpty()
			? Optional.empty()
			: Optional.of(relationship.memorialName());
	}
}
