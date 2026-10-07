package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * The departure board's ranking rules.
 *
 * The coordinates below are on a north-south line from the stop, so a vehicle at a lower latitude is
 * south of it: 0.0027 degrees is about 300 m and 0.0099 degrees is about 1100 m.
 */
public class StopDeparturesTest {

    private static final double STOP_LAT = 53.5786;
    private static final double STOP_LON = -0.6548;

    /** Roughly 111.19 m per thousandth of a degree of latitude. */
    private static double latOffsetFor(int metres) {
        return STOP_LAT - metres / 111194.9;
    }

    private static StopDepartures.Vehicle vehicle(String route, int metresSouth, float bearing, float kph) {
        return new StopDepartures.Vehicle("bus:" + route, route, "to City Centre",
                latOffsetFor(metresSouth), STOP_LON, bearing, kph);
    }

    private static List<String> routesOf(List<StopDepartures.Departure> board) {
        List<String> routes = new ArrayList<>();
        for (StopDepartures.Departure departure : board) {
            routes.add(departure.route);
        }
        return routes;
    }

    @Test
    public void approachingVehicleIsTimedFromItsOwnSpeed() {
        // 1100 m at 36 km/h (600 m per minute) is a little under two minutes
        List<StopDepartures.Departure> board = StopDepartures.board(
                Collections.singletonList(vehicle("350", 1100, 0f, 36f)),
                STOP_LAT, STOP_LON, "");

        assertEquals(1, board.size());
        assertEquals("2 min", board.get(0).minutesLabel());
        assertFalse(board.get(0).atStop);
    }

    @Test
    public void vehicleDrivingAwayFromTheStopIsNotADeparture() {
        // north of the stop and still heading north
        StopDepartures.Vehicle receding = new StopDepartures.Vehicle(
                "bus:350", "350", "to City Centre", latOffsetFor(-1100), STOP_LON, 0f, 36f);

        List<StopDepartures.Departure> board = StopDepartures.board(
                Collections.singletonList(receding), STOP_LAT, STOP_LON, "");

        assertTrue(board.isEmpty());
    }

    @Test
    public void vehicleAtTheStopIsDue() {
        StopDepartures.Vehicle atStop = new StopDepartures.Vehicle(
                "bus:350", "350", "to City Centre", STOP_LAT, STOP_LON, 90f, 0f);

        List<StopDepartures.Departure> board = StopDepartures.board(
                Collections.singletonList(atStop), STOP_LAT, STOP_LON, "");

        assertEquals(1, board.size());
        assertTrue(board.get(0).atStop);
        assertEquals("Due", board.get(0).minutesLabel());
        assertTrue(board.get(0).hasTime());
    }

    @Test
    public void stoppedVehicleKeepsItsDistanceAndClaimsNoTime() {
        // heading towards the stop but stationary, so no time can be estimated
        List<StopDepartures.Departure> board = StopDepartures.board(
                Collections.singletonList(vehicle("350", 300, 0f, 0f)),
                STOP_LAT, STOP_LON, "");

        assertEquals(1, board.size());
        assertEquals(-1, board.get(0).minutes);
        assertEquals("", board.get(0).minutesLabel());
        assertFalse(board.get(0).hasTime());
    }

    @Test
    public void unknownSpeedClaimsNoTime() {
        List<StopDepartures.Departure> board = StopDepartures.board(
                Collections.singletonList(vehicle("350", 300, 0f, Float.NaN)),
                STOP_LAT, STOP_LON, "");

        assertEquals("", board.get(0).minutesLabel());
    }

    @Test
    public void unknownHeadingIsNotTreatedAsDrivingAway() {
        // bearing is unknown, so the vehicle is kept and shown by distance alone
        List<StopDepartures.Departure> board = StopDepartures.board(
                Collections.singletonList(vehicle("350", 300, Float.NaN, Float.NaN)),
                STOP_LAT, STOP_LON, "");

        assertEquals(1, board.size());
    }

    @Test
    public void onlyRoutesThatUseTheStopAreListed() {
        List<StopDepartures.Vehicle> vehicles = Arrays.asList(
                vehicle("350", 300, 0f, 36f),
                vehicle("6", 200, 0f, 36f)); // nearer, but does not stop here

        List<StopDepartures.Departure> board = StopDepartures.board(
                vehicles, STOP_LAT, STOP_LON, "350, 4");

        assertEquals(Collections.singletonList("350"), routesOf(board));
    }

    @Test
    public void unknownStopRoutesAcceptEveryRoute() {
        List<StopDepartures.Vehicle> vehicles = Arrays.asList(
                vehicle("350", 300, 0f, 36f),
                vehicle("6", 200, 0f, 36f));

        assertEquals(Arrays.asList("6", "350"), routesOf(
                StopDepartures.board(vehicles, STOP_LAT, STOP_LON, "")));
    }

    @Test
    public void vehiclesBeyondTheRadiusAreLeftOffTheBoard() {
        List<StopDepartures.Departure> board = StopDepartures.board(
                Collections.singletonList(vehicle("350", 2000, 0f, 36f)),
                STOP_LAT, STOP_LON, "");

        assertTrue(board.isEmpty());
    }

    @Test
    public void boardIsOrderedSoonestFirstWithUntimedVehiclesLast() {
        List<StopDepartures.Vehicle> vehicles = Arrays.asList(
                vehicle("350", 1100, 0f, 36f),  // about 2 minutes
                vehicle("6", 300, 0f, 36f),     // about 1 minute
                vehicle("4", 400, 0f, 0f));     // stopped, so distance only

        List<StopDepartures.Departure> board = StopDepartures.board(
                vehicles, STOP_LAT, STOP_LON, "");

        assertEquals(Arrays.asList("6", "350", "4"), routesOf(board));
        assertEquals("4", board.get(2).route);
        assertFalse(board.get(2).hasTime());
    }

    @Test
    public void equalTimesAreBrokenByDistance() {
        List<StopDepartures.Vehicle> vehicles = Arrays.asList(
                vehicle("350", 500, 0f, 36f),
                vehicle("6", 200, 0f, 36f));

        List<StopDepartures.Departure> board = StopDepartures.board(
                vehicles, STOP_LAT, STOP_LON, "");

        assertEquals("1 min", board.get(0).minutesLabel());
        assertEquals("1 min", board.get(1).minutesLabel());
        assertEquals(Arrays.asList("6", "350"), routesOf(board));
    }

    @Test
    public void routeListUnderstandsBothSeparatorsAndJunk() {
        assertEquals(Arrays.asList("350", "4"),
                StopDepartures.parseRoutes("350, 4"));
        assertEquals(Arrays.asList("350", "4"),
                StopDepartures.parseRoutes("350;4"));
        assertEquals(Arrays.asList("X1", "X2"),
                StopDepartures.parseRoutes(" X1 ,; ,X2 "));
        assertTrue(StopDepartures.parseRoutes("").isEmpty());
        assertTrue(StopDepartures.parseRoutes(null).isEmpty());
    }

    @Test
    public void emptyFleetProducesAnEmptyBoard() {
        assertTrue(StopDepartures.board(new ArrayList<>(), STOP_LAT, STOP_LON, "350").isEmpty());
        assertTrue(StopDepartures.board(null, STOP_LAT, STOP_LON, "350").isEmpty());
    }
}
