package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Saving, identifying and forgetting bus stops. */
public class StopFavoritesTest {

    private static StopFavorites.FavoriteStop stop(long id, String name, String routes,
            double lat, double lon) {
        return new StopFavorites.FavoriteStop(id, name, routes, lat, lon);
    }

    private static List<String> keysOf(List<StopFavorites.FavoriteStop> stops) {
        List<String> keys = new ArrayList<>();
        for (StopFavorites.FavoriteStop stop : stops) {
            keys.add(stop.key());
        }
        return keys;
    }

    @Test
    public void identityPrefersTheOpenStreetMapId() {
        assertEquals("osm:12345", StopFavorites.keyFor(12345L, 53.5786, -0.6548));
    }

    @Test
    public void identityFallsBackToTheCoordinateWhenThereIsNoId() {
        // a stop read from a cache written before ids were kept must still be saveable
        String key = StopFavorites.keyFor(0L, 53.5786, -0.6548);
        assertEquals("ll:53.57860,-0.65480", key);
        assertEquals(key, StopFavorites.keyFor(0L, 53.5786, -0.6548));
    }

    @Test
    public void samePlaceWrittenTwiceKeepsOneEntry() {
        List<StopFavorites.FavoriteStop> saved = StopFavorites.with(
                new ArrayList<>(), stop(7L, "Byrd Road", "6, 350", 53.5786, -0.6548));
        saved = StopFavorites.with(saved, stop(7L, "Byrd Road", "6, 350, 4", 53.5786, -0.6548));

        assertEquals(1, saved.size());
        assertEquals("6, 350, 4", saved.get(0).routes);
    }

    @Test
    public void newestSaveComesFirst() {
        List<StopFavorites.FavoriteStop> saved = new ArrayList<>();
        saved = StopFavorites.with(saved, stop(1L, "First", "", 53.1, -0.1));
        saved = StopFavorites.with(saved, stop(2L, "Second", "", 53.2, -0.2));

        assertEquals(Arrays.asList("osm:2", "osm:1"), keysOf(saved));
    }

    @Test
    public void removingDropsOnlyTheMatchingStop() {
        List<StopFavorites.FavoriteStop> saved = Arrays.asList(
                stop(1L, "First", "", 53.1, -0.1),
                stop(2L, "Second", "", 53.2, -0.2));

        List<StopFavorites.FavoriteStop> left = StopFavorites.without(saved, "osm:1");

        assertEquals(Arrays.asList("osm:2"), keysOf(left));
        // the caller's list is left alone
        assertEquals(2, saved.size());
    }

    @Test
    public void containsMatchesOnIdentityNotOnTheObject() {
        List<StopFavorites.FavoriteStop> saved = Arrays.asList(stop(1L, "First", "", 53.1, -0.1));

        assertTrue(StopFavorites.contains(saved, "osm:1"));
        assertFalse(StopFavorites.contains(saved, "osm:2"));
        assertFalse(StopFavorites.contains(saved, null));
        assertFalse(StopFavorites.contains(null, "osm:1"));
    }

    @Test
    public void encodingRoundTripsEveryField() {
        List<StopFavorites.FavoriteStop> saved = Arrays.asList(
                stop(9470784L, "Byrd Road", "6, 350", 53.57861234, -0.65481234),
                stop(0L, "Unnamed stop", "", 53.1, -0.2));

        List<StopFavorites.FavoriteStop> read = StopFavorites.decode(StopFavorites.encode(saved));

        assertEquals(2, read.size());
        assertEquals(9470784L, read.get(0).osmId);
        assertEquals("Byrd Road", read.get(0).name);
        assertEquals("6, 350", read.get(0).routes);
        assertEquals(53.57861234, read.get(0).latitude, 1e-9);
        assertEquals(-0.65481234, read.get(0).longitude, 1e-9);
        assertEquals(keysOf(saved), keysOf(read));
        assertEquals(read.get(0).key(), keysOf(read).get(0));
    }

    @Test
    public void unreadableStorageDecodesToNothingRatherThanThrowing() {
        assertTrue(StopFavorites.decode(null).isEmpty());
        assertTrue(StopFavorites.decode("").isEmpty());
        assertTrue(StopFavorites.decode("not json at all").isEmpty());
        assertTrue(StopFavorites.decode("{\"unexpected\":true}").isEmpty());
    }

    @Test
    public void sortingIsAlphabeticalAndIndependentOfTheStoredOrder() {
        List<StopFavorites.FavoriteStop> saved = Arrays.asList(
                stop(3L, "Wharfdale Place", "", 53.3, -0.3),
                stop(1L, "Ashby Road", "", 53.1, -0.1),
                stop(2L, "byrd road", "", 53.2, -0.2));

        assertEquals(Arrays.asList("osm:1", "osm:2", "osm:3"), keysOf(StopFavorites.sorted(saved)));
    }

    @Test
    public void identicalNamesFallBackToAStableTieBreak() {
        List<StopFavorites.FavoriteStop> saved = Arrays.asList(
                stop(9L, "Bus Station", "", 53.1, -0.1),
                stop(4L, "Bus Station", "", 53.2, -0.2));

        assertEquals(Arrays.asList("osm:4", "osm:9"), keysOf(StopFavorites.sorted(saved)));
    }
}
