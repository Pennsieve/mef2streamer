package edu.upenn.cis.eeg.mef.mefstreamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the CHANNEL_META payload emitted by FrameStreamer.
 *
 * Downstream consumers (processor-mef-timeseries) parse this JSON to learn how
 * to interpret the raw int32 sample frames. The voltage conversion factor is
 * the only thing that turns those counts into microvolts, so it has to survive
 * the trip.
 */
public class FrameStreamerTest {

	@Test
	public void channelMetaIncludesVoltageConversionFactor() {
		String meta = FrameStreamer.channelMetaJson(
				"LA01", "SEEG", "Stereoelectroencephalography",
				0.1, 300.0, 512.0, 0.2987, 1000L, 2000L);

		assertTrue("expected voltage_conversion_factor in: " + meta,
				meta.contains("\"voltage_conversion_factor\":0.2987"));
	}

	@Test
	public void channelMetaStillReportsCountsAsTheSampleUnit() {
		// The sample frames really are raw counts — the unit must stay honest.
		// Claiming microvolts here would make consumers skip the scaling step.
		String meta = FrameStreamer.channelMetaJson(
				"LA01", "SEEG", "desc", 0.1, 300.0, 512.0, 0.25, 0L, 1L);

		assertTrue("expected counts unit in: " + meta, meta.contains("\"unit\":\"counts\""));
	}

	@Test
	public void channelMetaPreservesNegativeConversionFactor() {
		// Inverted polarity is encoded as a negative factor; dropping the sign
		// would flip every waveform with no other visible symptom.
		String meta = FrameStreamer.channelMetaJson(
				"LA01", "SEEG", "desc", 0.1, 300.0, 512.0, -0.5, 0L, 1L);

		assertTrue("expected negative factor in: " + meta,
				meta.contains("\"voltage_conversion_factor\":-0.5"));
	}

	@Test
	public void channelMetaRetainsExistingFields() {
		// Guards the fields mef_streamer.py already reads.
		String meta = FrameStreamer.channelMetaJson(
				"RA02", "ECOG", "Electrocorticography",
				0.5, 150.0, 1024.0, 0.25, 111L, 222L);

		assertTrue(meta.contains("\"name\":\"RA02\""));
		assertTrue(meta.contains("\"type\":\"ECOG\""));
		assertTrue(meta.contains("\"description\":\"Electrocorticography\""));
		assertTrue(meta.contains("\"low_cut_hz\":0.5"));
		assertTrue(meta.contains("\"high_cut_hz\":150.0"));
		assertTrue(meta.contains("\"rate_hz\":1024.0"));
		assertTrue(meta.contains("\"reported_start_us\":111"));
		assertTrue(meta.contains("\"reported_end_us\":222"));
	}

	@Test
	public void channelNameWithQuotesIsEscaped() {
		String meta = FrameStreamer.channelMetaJson(
				"odd\"name", "EEG", "desc", 0.1, 300.0, 512.0, 0.25, 0L, 1L);

		assertTrue("expected escaped name in: " + meta, meta.contains("\"name\":\"odd\\\"name\""));
	}

	@Test
	public void headerDefaultConversionFactorFlowsThrough() throws Exception {
		// A header with no explicit factor defaults to 1.0. That is a real value
		// downstream, not a signal of absence — this test records that so the
		// behavior is visible if it ever needs to change.
		MefHeader2 header = new MefHeader2();

		String meta = FrameStreamer.channelMetaJson(
				"LA01", "SEEG", "desc", 0.1, 300.0, 512.0,
				header.getVoltageConversionFactor(), 0L, 1L);

		assertEquals(1.0, header.getVoltageConversionFactor(), 1e-12);
		assertTrue("expected default factor in: " + meta,
				meta.contains("\"voltage_conversion_factor\":1.0"));
	}
}
