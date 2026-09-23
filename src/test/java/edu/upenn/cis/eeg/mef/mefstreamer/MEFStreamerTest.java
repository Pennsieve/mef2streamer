package edu.upenn.cis.eeg.mef.mefstreamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.Test;

/**
 * Tests that MEFStreamer refuses anything that isn't MEF2.
 *
 * The header fields live at fixed offsets, so a file in another format parses
 * without complaint into meaningless values and streams out as plausible
 * samples. Nothing downstream re-checks, which makes the constructor the last
 * cheap place to catch it.
 */
public class MEFStreamerTest {

	/** A 1024-byte MEF header with the version bytes set to whatever we want. */
	private static byte[] headerWithVersion(int major, int minor) {
		MefHeader2 header = new MefHeader2();
		header.setHeaderVersionMajor(major);
		header.setHeaderVersionMinor(minor);
		header.setSamplingFrequency(512.0);
		return header.serialize();
	}

	private static MEFStreamer open(byte[] header) throws IOException {
		return new MEFStreamer(new ByteArrayInputStream(header), false);
	}

	@Test
	public void acceptsMef2() throws IOException {
		MEFStreamer streamer = open(headerWithVersion(2, 1));

		assertEquals(2, streamer.getMEFHeader().getHeaderVersionMajor());
	}

	@Test
	public void rejectsMef3() {
		try {
			open(headerWithVersion(3, 0));
			fail("expected MEF3 to be rejected");
		} catch (IOException expected) {
			assertTrue("message should name the version found: " + expected.getMessage(),
					expected.getMessage().contains("3.0"));
			assertTrue("message should say what is supported: " + expected.getMessage(),
					expected.getMessage().contains("MEF2"));
		}
	}

	@Test
	public void rejectsMef1() {
		// MefHeader2 still carries branches for the pre-2.0 field ordering, but
		// they have no fixture and the RED block decode was never checked against
		// MEF1. Refusing beats emitting samples nobody has verified.
		try {
			open(headerWithVersion(1, 0));
			fail("expected MEF1 to be rejected");
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("1.0"));
		}
	}

	@Test
	public void rejectsAFileThatIsNotMefAtAll() {
		// Zeroed bytes read as major version 0 -- the shape of a wrong file
		// pointed at this thing by mistake.
		try {
			open(new byte[1024]);
			fail("expected a non-MEF file to be rejected");
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("Unsupported MEF version"));
		}
	}
}
