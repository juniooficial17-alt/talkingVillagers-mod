package com.talkingvillagers.book;

/**
 * Cleaning up a village name a player typed in chat.
 *
 * <p>Chat text can carry formatting codes and control characters that have no business in a
 * settlement's name, and nothing stops a player typing something absurdly long. Both are
 * stripped here rather than trusted from the caller, since this is the one place free text from
 * chat becomes a name shown all over the mod.
 */
final class Names {
	/** Longest a settlement name may be after cleaning. */
	private static final int MAX_LENGTH = 32;

	private Names() {
	}

	/**
	 * Strips formatting codes and control characters, then trims to {@link #MAX_LENGTH}.
	 *
	 * @return the cleaned name, or empty if nothing usable was left in it
	 */
	static String sanitise(String input) {
		StringBuilder cleaned = new StringBuilder(input.length());
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			// A formatting code is the section sign plus whatever follows it; both are dropped.
			if (c == '§') {
				i++;
				continue;
			}
			if (Character.isISOControl(c)) {
				continue;
			}
			cleaned.append(c);
		}

		String result = cleaned.toString().strip();
		return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH).strip() : result;
	}
}
