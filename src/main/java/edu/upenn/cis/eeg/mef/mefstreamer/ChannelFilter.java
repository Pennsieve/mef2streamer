package edu.upenn.cis.eeg.mef.mefstreamer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks which .mef files to convert from the MEF_CHANNELS environment variable.
 *
 * Channel names are matched against the .mef filename minus its extension --
 * the same string FrameStreamer emits as "name" in CHANNEL_META -- so what you
 * ask for here is what you see downstream. Matching is case-insensitive.
 */
final class ChannelFilter {

	/** Comma-separated channel names, e.g. MEF_CHANNELS=A1,B2. Unset or blank means convert everything. */
	static final String ENV_VAR = "MEF_CHANNELS";

	private ChannelFilter() {
	}

	/** Thrown when a requested channel has no matching .mef file. */
	static final class UnknownChannelException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		UnknownChannelException(String message) {
			super(message);
		}
	}

	/**
	 * Splits the env var on commas, trimming and dropping empties. Returns an
	 * empty list for null or blank, which callers read as "no filter".
	 */
	static List<String> parse(String value) {
		List<String> names = new ArrayList<String>();
		if (value == null) {
			return names;
		}
		for (String part : value.split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				names.add(trimmed);
			}
		}
		return names;
	}

	/**
	 * Narrows {@code mefFiles} to the requested channels, preserving the input
	 * file order. An empty request returns the files untouched.
	 *
	 * Throws if any requested channel is missing. Silently converting the subset
	 * we happened to find would look like success and hand downstream a
	 * recording that is quietly short a channel.
	 */
	static File[] select(File[] mefFiles, List<String> requested) {
		if (requested.isEmpty()) {
			return mefFiles;
		}

		// Fold both sides to lower case once; the caller's casing is preserved
		// for the error message only.
		Set<String> wanted = new LinkedHashSet<String>();
		for (String name : requested) {
			wanted.add(name.toLowerCase(Locale.ROOT));
		}

		List<File> selected = new ArrayList<File>();
		Set<String> matched = new LinkedHashSet<String>();
		for (File file : mefFiles) {
			String base = baseName(file.getName()).toLowerCase(Locale.ROOT);
			if (wanted.contains(base)) {
				selected.add(file);
				matched.add(base);
			}
		}

		List<String> missing = new ArrayList<String>();
		for (String name : requested) {
			if (!matched.contains(name.toLowerCase(Locale.ROOT))) {
				missing.add(name);
			}
		}
		if (!missing.isEmpty()) {
			throw new UnknownChannelException(
					"channel(s) not found in the input directory: " + join(missing)
							+ "; available: " + join(availableNames(mefFiles)));
		}

		return selected.toArray(new File[selected.size()]);
	}

	/** Filename without its extension. "A1.mef" -> "A1". */
	static String baseName(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	private static List<String> availableNames(File[] mefFiles) {
		List<String> names = new ArrayList<String>();
		for (File file : mefFiles) {
			names.add(baseName(file.getName()));
		}
		return names;
	}

	private static String join(List<String> values) {
		return values.isEmpty() ? "(none)" : Arrays.toString(values.toArray()).replaceAll("^\\[|\\]$", "");
	}
}
