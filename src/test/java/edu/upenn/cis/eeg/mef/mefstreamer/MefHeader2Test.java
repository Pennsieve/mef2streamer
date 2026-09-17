package edu.upenn.cis.eeg.mef.mefstreamer;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

/**
 * Tests for MefHeader2, focused on the voltage conversion factor.
 *
 * The voltage conversion factor is the microvolts-per-A/D-unit scale for a
 * channel. Sample values are raw integer counts and are meaningless as a
 * physical measurement until multiplied by it, so anything reading MEF and
 * emitting voltages depends on it being parsed correctly.
 */
public class MefHeader2Test {

	/** Documented byte offset of the voltage conversion factor within the 1024-byte header. */
	private static final int VCF_OFFSET = 456;

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
}
