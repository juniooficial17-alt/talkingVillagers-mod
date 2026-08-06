package com.talkingvillagers.book;

import java.util.ArrayList;
import java.util.List;

/**
 * Working out where a book's pages have to break.
 *
 * <p>Kept free of any Minecraft type on purpose: this is arithmetic about how much text fits on a
 * page, it is the part most likely to be quietly wrong, and separating it means it can be tested
 * without a running game.
 *
 * <p>Getting it wrong is not a cosmetic problem. A book page that overflows is not truncated with
 * an ellipsis or a warning — the client simply stops drawing, so a villager's history appears to
 * end early and nothing hints that there was more.
 */
final class Pagination {
	/**
	 * Rough width of a book page in characters.
	 *
	 * <p>Approximate because Minecraft's font is proportional — an "i" is nothing like a "W" — so
	 * this deliberately errs narrow. Breaking a page one line early is invisible; breaking it one
	 * line late loses text.
	 */
	private static final int PAGE_WIDTH_CHARS = 19;

	/** Display lines that fit on one page. */
	private static final int PAGE_LINES = 14;

	/** Lines given over to a heading and the blank line under it, charged to every page. */
	private static final int HEADING_LINES = 2;

	private Pagination() {
	}

	/**
	 * Splits lines into per-page groups, each of which fits under a heading.
	 *
	 * <p>A single line too long for a whole page still gets its own page rather than being dropped
	 * or splitting the run: it will overflow, but one over-long memory losing its tail is a far
	 * smaller failure than an empty page that silently swallows everything after it.
	 *
	 * @return one group per page, in order; empty if there is nothing to lay out
	 */
	static List<List<String>> group(List<String> lines) {
		List<List<String>> pages = new ArrayList<>();
		if (lines.isEmpty()) {
			return pages;
		}

		int budget = PAGE_LINES - HEADING_LINES;
		List<String> current = new ArrayList<>();
		int used = 0;

		for (String line : lines) {
			int cost = displayLines(line);
			if (!current.isEmpty() && used + cost > budget) {
				pages.add(current);
				current = new ArrayList<>();
				used = 0;
			}
			current.add(line);
			used += cost;
		}
		pages.add(current);
		return pages;
	}

	/** How many wrapped lines one entry takes up. A blank line still occupies one. */
	static int displayLines(String line) {
		return Math.max(1, (line.length() + PAGE_WIDTH_CHARS - 1) / PAGE_WIDTH_CHARS);
	}
}
