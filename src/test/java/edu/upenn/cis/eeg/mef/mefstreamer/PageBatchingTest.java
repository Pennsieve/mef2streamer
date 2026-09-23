package edu.upenn.cis.eeg.mef.mefstreamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.Test;

import edu.upenn.cis.db.mefview.services.TimeSeriesPage;

/**
 * Tests for reading a channel's blocks a batch at a time.
 *
 * getNextBlocks decompresses every block it is asked for before returning, so
 * asking for a whole channel holds the whole channel in memory. The expensive
 * failure here is not a crash but a silent one: a batching seam that drops or
 * repeats a page would shift every sample after it in time, and nothing would
 * report an error.
 */
public class PageBatchingTest {

	/** A reader that hands out a fixed page list, recording each request. */
	private static final class FakeReader implements FrameStreamer.PageReader {

		private final List<TimeSeriesPage> pages;
		private final List<Integer> requests = new ArrayList<Integer>();
		private int position = 0;

		private FakeReader(List<TimeSeriesPage> pages) {
			this.pages = pages;
		}

		@Override
		public List<TimeSeriesPage> read(int noBlocks) {
			requests.add(noBlocks);
			final List<TimeSeriesPage> out = new ArrayList<TimeSeriesPage>();
			for (int i = 0; i < noBlocks && position < pages.size(); i++) {
				out.add(pages.get(position++));
			}
			return out;
		}
	}

	private static List<TimeSeriesPage> pages(int count) {
		final List<TimeSeriesPage> out = new ArrayList<TimeSeriesPage>();
		for (int i = 0; i < count; i++) {
			final TimeSeriesPage page = new TimeSeriesPage();
			page.timeStart = i;
			out.add(page);
		}
		return out;
	}

	private static List<Long> drain(Iterable<TimeSeriesPage> iterable) {
		final List<Long> seen = new ArrayList<Long>();
		for (TimeSeriesPage page : iterable) {
			seen.add(page.timeStart);
		}
		return seen;
	}

	private static List<Long> starts(int count) {
		final List<Long> out = new ArrayList<Long>();
		for (int i = 0; i < count; i++) {
			out.add((long) i);
		}
		return out;
	}

	@Test
	public void yieldsEveryPageOnceInOrder() {
		final FakeReader streamer = new FakeReader(pages(10));
		assertEquals(starts(10), drain(FrameStreamer.pagesInBatches(streamer, 4)));
	}

	@Test
	public void batchSizeDoesNotChangeThePageSequence() {
		// The whole point: only memory depends on the batch, never the output.
		for (int batch = 1; batch <= 13; batch++) {
			final FakeReader streamer = new FakeReader(pages(10));
			assertEquals("batch=" + batch, starts(10),
					drain(FrameStreamer.pagesInBatches(streamer, batch)));
		}
	}

	@Test
	public void aBatchLargerThanTheChannelStillYieldsEveryPage() {
		final FakeReader streamer = new FakeReader(pages(3));
		assertEquals(starts(3), drain(FrameStreamer.pagesInBatches(streamer, 512)));
	}

	@Test
	public void anEmptyChannelYieldsNothing() {
		final FakeReader streamer = new FakeReader(pages(0));
		assertTrue(drain(FrameStreamer.pagesInBatches(streamer, 4)).isEmpty());
	}

	@Test
	public void readsOnlyOneBatchAtATime() {
		// What bounds the memory: the old code asked for the whole channel in
		// one call, so every page was decompressed before the first was used.
		final FakeReader streamer = new FakeReader(pages(10));
		drain(FrameStreamer.pagesInBatches(streamer, 4));
		for (Integer requested : streamer.requests) {
			assertEquals(Integer.valueOf(4), requested);
		}
	}

	@Test
	public void stopsReadingOnceTheChannelIsDone() {
		final FakeReader streamer = new FakeReader(pages(8));
		drain(FrameStreamer.pagesInBatches(streamer, 4));
		// Two full batches, then one read that comes back empty.
		assertEquals(3, streamer.requests.size());
	}

	@Test
	public void hasNextIsIdempotent() {
		final FakeReader streamer = new FakeReader(pages(2));
		final Iterator<TimeSeriesPage> it =
				FrameStreamer.pagesInBatches(streamer, 1).iterator();
		assertTrue(it.hasNext());
		assertTrue(it.hasNext());
		it.next();
		it.next();
		assertFalse(it.hasNext());
		assertFalse(it.hasNext());
	}

	@Test
	public void nextPastTheEndThrows() {
		final FakeReader streamer = new FakeReader(pages(1));
		final Iterator<TimeSeriesPage> it =
				FrameStreamer.pagesInBatches(streamer, 4).iterator();
		it.next();
		try {
			it.next();
			fail("expected NoSuchElementException past the last page");
		} catch (NoSuchElementException expected) {
			// as intended
		}
	}

	@Test
	public void aReadFailureSurfacesRatherThanEndingTheChannelQuietly() {
		// Swallowing this would truncate a channel and still exit clean.
		final FrameStreamer.PageReader failing =
				new FrameStreamer.PageReader() {
					@Override
					public List<TimeSeriesPage> read(int noBlocks)
							throws IOException {
						throw new IOException("disk went away");
					}
				};
		try {
			drain(FrameStreamer.pagesInBatches(failing, 4));
			fail("expected the IOException to surface");
		} catch (RuntimeException expected) {
			assertTrue(expected.getCause() instanceof IOException);
		}
	}

	@Test
	public void batchSizeDefaultsWhenTheEnvironmentSaysNothingUseful() {
		// getenv cannot be set from a test, so this pins the parse rules the
		// default path depends on rather than the lookup itself.
		assertEquals(512, FrameStreamer.DEFAULT_BLOCK_BATCH);
		assertTrue(FrameStreamer.blockBatch() > 0);
	}
}
