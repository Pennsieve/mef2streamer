package edu.upenn.cis.eeg.mef.mefstreamer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests for the MEF_CHANNELS filter.
 *
 * The expensive failure here is a silent one: a run that converts fewer
 * channels than asked for and still exits clean. Several of these pin down that
 * a mismatch stops the run.
 */
public class ChannelFilterTest {

	private static final File[] FILES = files("A1.mef", "B2.mef", "LA01.mef");

	private static File[] files(String... names) {
		File[] out = new File[names.length];
		for (int i = 0; i < names.length; i++) {
			out[i] = new File("/data/input", names[i]);
		}
		return out;
	}

	private static List<String> names(File[] selected) {
		String[] out = new String[selected.length];
		for (int i = 0; i < selected.length; i++) {
			out[i] = selected[i].getName();
		}
		return Arrays.asList(out);
	}

	@Test
	public void parseSplitsOnCommasAndTrims() {
		assertEquals(Arrays.asList("A1", "B2"), ChannelFilter.parse(" A1 , B2 "));
	}

	@Test
	public void parseIgnoresEmptyEntries() {
		// A trailing comma is the usual way this shows up in a compose file.
		assertEquals(Arrays.asList("A1"), ChannelFilter.parse("A1,,  ,"));
	}

	@Test
	public void parseTreatsUnsetAndBlankAsNoFilter() {
		assertTrue(ChannelFilter.parse(null).isEmpty());
		assertTrue(ChannelFilter.parse("").isEmpty());
		assertTrue(ChannelFilter.parse("   ").isEmpty());
	}

	@Test
	public void noFilterReturnsEveryFile() {
		assertArrayEquals(FILES, ChannelFilter.select(FILES, Collections.<String>emptyList()));
	}

	@Test
	public void selectsOnlyTheRequestedChannels() {
		File[] selected = ChannelFilter.select(FILES, Arrays.asList("A1", "B2"));

		assertEquals(Arrays.asList("A1.mef", "B2.mef"), names(selected));
	}

	@Test
	public void matchingIsCaseInsensitive() {
		File[] selected = ChannelFilter.select(FILES, Arrays.asList("la01"));

		assertEquals(Arrays.asList("LA01.mef"), names(selected));
	}

	@Test
	public void selectionKeepsInputFileOrderNotRequestOrder() {
		// EDFBuilder streams channels in array order; the request order is not
		// meant to reorder the output.
		File[] selected = ChannelFilter.select(FILES, Arrays.asList("LA01", "A1"));

		assertEquals(Arrays.asList("A1.mef", "LA01.mef"), names(selected));
	}

	@Test
	public void unknownChannelStopsTheRun() {
		try {
			ChannelFilter.select(FILES, Arrays.asList("A1", "ZZ9"));
			fail("expected an UnknownChannelException for ZZ9");
		} catch (ChannelFilter.UnknownChannelException expected) {
			assertTrue("message should name the missing channel: " + expected.getMessage(),
					expected.getMessage().contains("ZZ9"));
			assertTrue("message should list what was available: " + expected.getMessage(),
					expected.getMessage().contains("LA01"));
		}
	}

	@Test
	public void oneBadNameFailsEvenWhenTheOthersMatch() {
		// Converting the subset that happened to match would look like success.
		try {
			ChannelFilter.select(FILES, Arrays.asList("A1", "B2", "LA01", "typo"));
			fail("expected an UnknownChannelException");
		} catch (ChannelFilter.UnknownChannelException expected) {
			assertTrue(expected.getMessage().contains("typo"));
		}
	}

	@Test
	public void channelNamesMayContainSpacesAndPunctuation() {
		// Real iEEG.org exports look like "EEG AD 01-Ref.mef". Only whitespace
		// at the edges of a comma-separated entry is trimmed; spaces inside a
		// name are part of it.
		File[] clinical = files("EEG AD 01-Ref.mef", "EEG PT 01-Ref.mef");

		File[] selected = ChannelFilter.select(clinical,
				ChannelFilter.parse("eeg ad 01-ref , EEG PT 01-Ref"));

		assertEquals(Arrays.asList("EEG AD 01-Ref.mef", "EEG PT 01-Ref.mef"), names(selected));
	}

	@Test
	public void baseNameKeepsDotsInsideTheName() {
		assertEquals("EEG AD 01-Ref", ChannelFilter.baseName("EEG AD 01-Ref.mef"));
		assertEquals("v1.2", ChannelFilter.baseName("v1.2.mef"));
	}

	@Test
	public void baseNameStripsOnlyTheExtension() {
		assertEquals("A1", ChannelFilter.baseName("A1.mef"));
		assertEquals("sub-001_run-2", ChannelFilter.baseName("sub-001_run-2.mef"));
		assertEquals("noext", ChannelFilter.baseName("noext"));
	}
}
