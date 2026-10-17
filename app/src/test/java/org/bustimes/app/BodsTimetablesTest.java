package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class BodsTimetablesTest {

    private static BodsTimetables.Dataset dataset(long id, String operator, String... lines) {
        return new BodsTimetables.Dataset(id, "2026-09-09T13:27:43+00:00", operator, "", "https://x/" + id,
                "zip", new ArrayList<>(Arrays.asList(lines)));
    }

    @Test
    public void aDatasetIsUsedOnlyWhenItPublishesOneOfTheStopsRoutes() {
        BodsTimetables.Dataset hornsby = dataset(25032, "Hornsby Travel", "4", "6", "x4");
        assertTrue(hornsby.serves("6"));
        assertTrue(hornsby.serves(" 6 "));
        assertTrue(hornsby.serves("X4"));
        assertFalse(hornsby.serves("350"));
        assertFalse(hornsby.serves(""));
        assertFalse(hornsby.serves(null));
    }

    @Test
    public void onlyTheDatasetsForTheStopsRoutesAreFetchedSmallestFirst() {
        List<BodsTimetables.Dataset> all = Arrays.asList(
                dataset(25911, "Stagecoach", "350", "1", "2", "3", "4"),
                dataset(25032, "Hornsby Travel", "4", "6"),
                dataset(14667, "East Yorkshire", "X5"));
        List<BodsTimetables.Dataset> wanted =
                BodsTimetables.forRoutes(all, Arrays.asList("350", "6"));
        assertEquals(2, wanted.size());
        // the smaller timetable is asked for first, so times appear sooner
        assertEquals("Hornsby Travel", wanted.get(0).label());
        assertEquals("Stagecoach", wanted.get(1).label());
    }

    @Test
    public void aDatasetKeepsOneFilePerUpload() throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir"), "bustimes-test-" + System.nanoTime());
        assertTrue(directory.mkdirs());
        BodsTimetables.Dataset first = dataset(25032, "Hornsby Travel", "4", "6");
        BodsTimetables.Dataset reuploaded = new BodsTimetables.Dataset(25032,
                "2026-10-17T09:00:00+00:00", "Hornsby Travel", "", "https://x/25032", "zip",
                new ArrayList<>(Arrays.asList("4", "6")));

        File file = BodsTimetables.datasetFile(directory, first);
        assertEquals("dataset-25032-20260909T1327430000.zip", file.getName());
        // a new upload gets its own name, so the old file can never be served as the new timetable
        assertFalse(BodsTimetables.datasetFile(directory, reuploaded).getName().equals(file.getName()));
    }

    @Test
    public void aSavedTimetableIsReusedUntilItAgesOut() throws Exception {
        File file = File.createTempFile("dataset-1-", ".zip");
        try {
            assertTrue(BodsTimetables.isFresh(file));
            assertTrue(file.setLastModified(System.currentTimeMillis()
                    - BodsTimetables.FILE_TTL_MS - 60_000));
            assertFalse(BodsTimetables.isFresh(file));
        } finally {
            file.delete();
        }
    }

    @Test
    public void noMatchingDatasetLeavesNothingToFetch() {
        List<BodsTimetables.Dataset> all = Arrays.asList(dataset(1, "Someone", "99"));
        assertTrue(BodsTimetables.forRoutes(all, Arrays.asList("350")).isEmpty());
        assertTrue(BodsTimetables.forRoutes(all, new ArrayList<String>()).isEmpty());
    }
}
