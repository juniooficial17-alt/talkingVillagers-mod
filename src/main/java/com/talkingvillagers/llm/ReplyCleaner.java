package com.talkingvillagers.llm;

/**
 * Turns whatever the model produced into a single line of speech fit to float above a
 * villager's head.
 *
 * <p>Small models leak their scaffolding constantly — wrapping replies in quotes, prefixing
 * them with the character's name, narrating actions in asterisks, or answering with a
 * paragraph when asked for a sentence. Rather than fight that with ever-longer prompts, the
 * output is normalised here, where the rules are cheap and testable.
 */
public final class ReplyCleaner {
	/** Hard ceiling on displayed speech, independent of the token limit. */
	public static final int MAX_LENGTH = 150;

	/**
	 * Sentences kept at most.
	 *
	 * <p>The prompt asks for exactly one, but a small model obliges only most of the time, and a
	 * villager delivering a paragraph while the player stands waiting is worse than one cut
	 * short. The token limit alone cannot do this job: it truncates mid-word rather than at a
	 * sentence.
	 */
	private static final int MAX_SENTENCES = 1;

	private ReplyCleaner() {
	}

	public static String clean(String raw) {
		if (raw == null) {
			return "";
		}

		String text = raw.strip();
		text = stripRoleplayAsterisks(text);
		text = text.replaceAll("\\s+", " ").strip();
		text = stripSurroundingQuotes(text);
		text = stripSpeakerPrefix(text);
		text = limitSentences(text);
		text = text.strip();

		if (text.length() > MAX_LENGTH) {
			text = truncateAtSentence(text);
		}
		return text;
	}

	/**
	 * Keeps only the first {@link #MAX_SENTENCES} sentences.
	 *
	 * <p>A run of terminators followed by whitespace — or by nothing — closes a sentence. An
	 * earlier version only counted a full stop when a capital letter followed, to spare
	 * abbreviations like "Mr."; that let a second sentence through whenever the model wrote it
	 * lowercase, and one sentence is a hard promise. Punctuation glued to the next character
	 * ("3.5 emeralds") still does not count.
	 */
	private static String limitSentences(String text) {
		int sentences = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c != '.' && c != '!' && c != '?') {
				continue;
			}

			// Skip runs of terminators, as in "what?!".
			int end = i;
			while (end + 1 < text.length() && ".!?".indexOf(text.charAt(end + 1)) >= 0) {
				end++;
			}

			boolean endsHere = end + 1 >= text.length();
			if (!endsHere && !Character.isWhitespace(text.charAt(end + 1))) {
				i = end;
				continue;
			}

			if (++sentences >= MAX_SENTENCES || endsHere) {
				return text.substring(0, end + 1);
			}
			i = end;
		}
		return text;
	}

	/**
	 * Removes {@code *shrugs*}-style stage directions. Only balanced pairs are touched, so a
	 * lone asterisk in ordinary text is left alone.
	 */
	private static String stripRoleplayAsterisks(String text) {
		if (text.indexOf('*') < 0) {
			return text;
		}
		return text.replaceAll("\\*[^*]{0,80}\\*", " ");
	}

	private static String stripSurroundingQuotes(String text) {
		String result = text;
		// Repeat: models sometimes double up, as in ""Good morning"".
		while (result.length() >= 2) {
			char first = result.charAt(0);
			char last = result.charAt(result.length() - 1);
			boolean quoted = (first == '"' && last == '"')
				|| (first == '\'' && last == '\'')
				|| (first == '“' && last == '”');
			if (!quoted) {
				break;
			}
			result = result.substring(1, result.length() - 1).strip();
		}
		return result;
	}

	/**
	 * Drops a leading {@code Name:} speaker label.
	 *
	 * <p>The prefix must actually look like a name — one or two capitalised words — because
	 * villagers say things like "I'll tell you this: it's cold", and a looser rule throws away
	 * everything before the colon. That was a real bug: any clause ending in a colon lost its
	 * first half.
	 */
	private static final java.util.regex.Pattern SPEAKER_PREFIX =
		java.util.regex.Pattern.compile("[A-Z][A-Za-z\\-]{0,15}( [A-Z][A-Za-z\\-]{0,15})?");

	private static String stripSpeakerPrefix(String text) {
		int colon = text.indexOf(':');
		if (colon <= 0 || colon > 24) {
			return text;
		}
		if (SPEAKER_PREFIX.matcher(text.substring(0, colon)).matches()) {
			return text.substring(colon + 1).strip();
		}
		return text;
	}

	/**
	 * Trims to the last sentence that fits, so a cut-off reply ends cleanly instead of
	 * mid-word. Falls back to a word boundary and an ellipsis when there is no sentence break.
	 */
	private static String truncateAtSentence(String text) {
		String window = text.substring(0, MAX_LENGTH);
		int lastStop = Math.max(window.lastIndexOf('.'), Math.max(window.lastIndexOf('!'), window.lastIndexOf('?')));
		if (lastStop >= MAX_LENGTH / 3) {
			return window.substring(0, lastStop + 1);
		}

		int lastSpace = window.lastIndexOf(' ');
		String trimmed = lastSpace > 0 ? window.substring(0, lastSpace) : window;
		return trimmed.strip() + "…";
	}
}
