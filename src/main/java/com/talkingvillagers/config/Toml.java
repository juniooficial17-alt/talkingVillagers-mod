package com.talkingvillagers.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal TOML reader covering the subset this mod's config uses: comments,
 * {@code [dotted.section]} headers, and {@code key = value} pairs whose values are
 * strings, booleans, numbers, or (possibly multi-line) arrays of those.
 *
 * <p>Neither Minecraft nor Fabric Loader ships a TOML library, and jar-in-jarring one for
 * a flat config of scalars and string lists is not worth the weight. Anything outside the
 * supported subset (inline tables, array-of-tables, dates, multi-line basic strings) is
 * rejected with a {@link TomlException} naming the line, rather than silently misparsed.
 */
public final class Toml {
	private Toml() {
	}

	/** Thrown when input is outside the supported subset or is malformed. */
	public static class TomlException extends RuntimeException {
		public TomlException(String message) {
			super(message);
		}
	}

	/**
	 * Parses TOML text into a flat map keyed by dotted path, e.g. {@code "ollama.base_url"}.
	 * Flat keys keep lookup trivial for typed accessors; no nested map walking is needed.
	 */
	public static Map<String, Object> parse(String text) {
		Map<String, Object> out = new LinkedHashMap<>();
		String section = "";
		List<String> lines = List.of(text.split("\n", -1));

		for (int i = 0; i < lines.size(); i++) {
			String raw = lines.get(i);
			String line = stripComment(raw).trim();
			if (line.isEmpty()) {
				continue;
			}

			if (line.startsWith("[")) {
				if (line.startsWith("[[")) {
					throw new TomlException("array-of-tables is not supported (line " + (i + 1) + ")");
				}
				if (!line.endsWith("]")) {
					throw new TomlException("unterminated section header (line " + (i + 1) + ")");
				}
				section = line.substring(1, line.length() - 1).trim();
				if (section.isEmpty()) {
					throw new TomlException("empty section header (line " + (i + 1) + ")");
				}
				continue;
			}

			int eq = line.indexOf('=');
			if (eq < 0) {
				throw new TomlException("expected 'key = value' (line " + (i + 1) + "): " + line);
			}
			String key = unquote(line.substring(0, eq).trim());
			if (key.isEmpty()) {
				throw new TomlException("empty key (line " + (i + 1) + ")");
			}
			String valueText = line.substring(eq + 1).trim();

			// An array may span lines; keep consuming until the brackets balance.
			if (valueText.startsWith("[") && !bracketsBalanced(valueText)) {
				StringBuilder sb = new StringBuilder(valueText);
				while (!bracketsBalanced(sb.toString())) {
					i++;
					if (i >= lines.size()) {
						throw new TomlException("unterminated array for key '" + key + "'");
					}
					sb.append('\n').append(stripComment(lines.get(i)).trim());
				}
				valueText = sb.toString();
			}

			String path = section.isEmpty() ? key : section + "." + key;
			out.put(path, parseValue(valueText, i + 1));
		}

		return out;
	}

	/**
	 * Removes a trailing {@code #} comment, ignoring hashes inside quoted strings so that
	 * values such as {@code name = "block #3"} survive intact.
	 */
	private static String stripComment(String line) {
		boolean inString = false;
		char quote = 0;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (inString) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					inString = false;
				}
			} else if (c == '"' || c == '\'') {
				inString = true;
				quote = c;
			} else if (c == '#') {
				return line.substring(0, i);
			}
		}
		return line;
	}

	/** True when every {@code [} in the (comment-stripped) text has a matching {@code ]}. */
	private static boolean bracketsBalanced(String text) {
		int depth = 0;
		boolean inString = false;
		char quote = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inString) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					inString = false;
				}
			} else if (c == '"' || c == '\'') {
				inString = true;
				quote = c;
			} else if (c == '[') {
				depth++;
			} else if (c == ']') {
				depth--;
			}
		}
		return depth == 0;
	}

	private static Object parseValue(String text, int line) {
		if (text.isEmpty()) {
			throw new TomlException("missing value (line " + line + ")");
		}

		if (text.startsWith("[")) {
			if (!text.endsWith("]")) {
				throw new TomlException("malformed array (line " + line + ")");
			}
			List<Object> list = new ArrayList<>();
			for (String element : splitTopLevel(text.substring(1, text.length() - 1))) {
				String trimmed = element.trim();
				if (!trimmed.isEmpty()) {
					list.add(parseValue(trimmed, line));
				}
			}
			return list;
		}

		if (text.startsWith("{")) {
			throw new TomlException("inline tables are not supported; use a [section] instead (line " + line + ")");
		}

		if (text.startsWith("\"") || text.startsWith("'")) {
			return unquote(text);
		}

		if (text.equals("true")) {
			return Boolean.TRUE;
		}
		if (text.equals("false")) {
			return Boolean.FALSE;
		}

		String numeric = text.replace("_", "");
		try {
			if (numeric.contains(".") || numeric.contains("e") || numeric.contains("E")) {
				return Double.parseDouble(numeric);
			}
			return Long.parseLong(numeric);
		} catch (NumberFormatException e) {
			throw new TomlException("unrecognised value (line " + line + "): " + text);
		}
	}

	/** Splits on commas that are outside any string or nested bracket. */
	private static List<String> splitTopLevel(String text) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int depth = 0;
		boolean inString = false;
		char quote = 0;

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inString) {
				current.append(c);
				if (c == '\\' && i + 1 < text.length()) {
					current.append(text.charAt(++i));
				} else if (c == quote) {
					inString = false;
				}
				continue;
			}
			switch (c) {
				case '"', '\'' -> {
					inString = true;
					quote = c;
					current.append(c);
				}
				case '[', '{' -> {
					depth++;
					current.append(c);
				}
				case ']', '}' -> {
					depth--;
					current.append(c);
				}
				case ',' -> {
					if (depth == 0) {
						parts.add(current.toString());
						current.setLength(0);
					} else {
						current.append(c);
					}
				}
				default -> current.append(c);
			}
		}
		parts.add(current.toString());
		return parts;
	}

	/** Strips surrounding quotes and expands escapes in basic (double-quoted) strings. */
	private static String unquote(String text) {
		if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
			return text.substring(1, text.length() - 1);
		}
		if (text.length() < 2 || !text.startsWith("\"") || !text.endsWith("\"")) {
			return text;
		}

		String body = text.substring(1, text.length() - 1);
		StringBuilder sb = new StringBuilder(body.length());
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c != '\\' || i + 1 >= body.length()) {
				sb.append(c);
				continue;
			}
			char next = body.charAt(++i);
			switch (next) {
				case 'n' -> sb.append('\n');
				case 't' -> sb.append('\t');
				case 'r' -> sb.append('\r');
				case '"' -> sb.append('"');
				case '\\' -> sb.append('\\');
				case 'u' -> {
					if (i + 4 < body.length()) {
						sb.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
						i += 4;
					}
				}
				default -> sb.append(next);
			}
		}
		return sb.toString();
	}

	/** Quotes and escapes a string for writing back out as a TOML basic string. */
	public static String quote(String value) {
		StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\t' -> sb.append("\\t");
				case '\r' -> sb.append("\\r");
				default -> sb.append(c);
			}
		}
		return sb.append('"').toString();
	}
}
