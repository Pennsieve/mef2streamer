package edu.upenn.cis.eeg.mef.mefstreamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Tests for MefHeader2, focused on the voltage conversion factor and on the
 * three duplicated copies of this class.
 *
 * The voltage conversion factor is the microvolts-per-A/D-unit scale for a
 * channel. Sample values are raw integer counts and are meaningless as a
 * physical measurement until multiplied by it, so anything reading MEF and
 * emitting voltages depends on it being parsed correctly.
 */
public class MefHeader2Test {

	/** Documented byte offset of the voltage conversion factor within the 1024-byte header. */
	private static final int VCF_OFFSET = 456;

	/** The three copies of MefHeader2 in this repo, all identical but for their package line. */
	private static final List<String> HEADER_COPIES = Arrays.asList(
			"src/main/java/MefHeader2.java",
			"src/main/java/edu/upenn/cis/eeg/mef/MefHeader2.java",
			"src/main/java/edu/upenn/cis/eeg/mef/mefstreamer/MefHeader2.java");

	@Test
	public void voltageConversionFactorSurvivesSerializeAndParse() throws IOException {
		MefHeader2 header = new MefHeader2();
		header.setVoltageConversionFactor(0.2987);

		MefHeader2 parsed = new MefHeader2(header.serialize());

		assertEquals(0.2987, parsed.getVoltageConversionFactor(), 1e-12);
	}

	@Test
	public void voltageConversionFactorIsWrittenAtDocumentedOffset() {
		MefHeader2 header = new MefHeader2();
		header.setVoltageConversionFactor(0.25);

		byte[] serialized = header.serialize();

		// MEF stores the factor as a little-endian IEEE-754 double at offset 456.
		long bits = 0;
		for (int i = 7; i >= 0; i--) {
			bits = (bits << 8) | (serialized[VCF_OFFSET + i] & 0xFFL);
		}

		assertEquals(0.25, Double.longBitsToDouble(bits), 1e-12);
		assertEquals(VCF_OFFSET, MefHeader2.VOLTAGE_CONVERSION_FACTOR_OFFSET);
		assertEquals(8, MefHeader2.VOLTAGE_CONVERSION_FACTOR_LENGTH);
	}

	@Test
	public void negativeVoltageConversionFactorIsPreserved() throws IOException {
		// Some acquisition systems record an inverted polarity as a negative
		// factor. Dropping the sign silently flips every waveform.
		MefHeader2 header = new MefHeader2();
		header.setVoltageConversionFactor(-0.5);

		MefHeader2 parsed = new MefHeader2(header.serialize());

		assertEquals(-0.5, parsed.getVoltageConversionFactor(), 1e-12);
	}

	@Test
	public void defaultVoltageConversionFactorIsOne() {
		// A default of 1.0 is indistinguishable from "one microvolt per count".
		// Callers cannot tell an unset factor from a real one, so a header that
		// failed to populate yields counts passed off as microvolts.
		assertEquals(1.0, new MefHeader2().getVoltageConversionFactor(), 1e-12);
	}

	/**
	 * This repo carries three copies of MefHeader2, differing only in their
	 * package declaration. EDFBuilder resolves the same-package copy by proximity
	 * rather than by import, so editing the wrong one changes nothing at runtime
	 * and produces no compile error. This test fails when the copies drift.
	 */
	@Test
	public void allMefHeader2CopiesAreIdenticalApartFromPackageDeclaration() throws IOException {
		List<String> reference = null;
		String referencePath = null;

		for (String path : HEADER_COPIES) {
			File file = new File(path);
			assertTrue(
					"Expected " + path + " — run tests from the project root so the source tree is visible",
					file.isFile());

			List<String> body = significantLines(file);
			if (reference == null) {
				reference = body;
				referencePath = path;
				continue;
			}

			assertEquals(describeDrift(referencePath, reference, path, body), reference.size(), body.size());
			for (int i = 0; i < reference.size(); i++) {
				if (!reference.get(i).equals(body.get(i))) {
					throw new AssertionError(describeDrift(referencePath, reference, path, body));
				}
			}
		}
	}

	/**
	 * Reports the first divergence only. Comparing the lists directly produces a
	 * thousand-line dump that buries the one line that actually differs.
	 */
	private static String describeDrift(String refPath, List<String> ref, String otherPath, List<String> other) {
		StringBuilder message = new StringBuilder();
		message.append(otherPath).append(" has drifted from ").append(refPath)
				.append(". These copies must stay in sync: EDFBuilder picks one by package proximity, ")
				.append("so a fix applied to only one copy silently does nothing.");

		int limit = Math.min(ref.size(), other.size());
		for (int i = 0; i < limit; i++) {
			if (!ref.get(i).equals(other.get(i))) {
				message.append("\n  first difference at significant line ").append(i + 1)
						.append("\n    ").append(refPath).append(": ").append(ref.get(i))
						.append("\n    ").append(otherPath).append(": ").append(other.get(i));
				return message.toString();
			}
		}
		message.append("\n  ").append(refPath).append(" has ").append(ref.size())
				.append(" significant lines, ").append(otherPath).append(" has ").append(other.size());
		return message.toString();
	}

	/** Source lines with package declarations, blank lines, and indentation removed. */
	private static List<String> significantLines(File file) throws IOException {
		List<String> significant = new ArrayList<String>();
		for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("package ")) {
				continue;
			}
			significant.add(trimmed);
		}
		return significant;
	}
}
