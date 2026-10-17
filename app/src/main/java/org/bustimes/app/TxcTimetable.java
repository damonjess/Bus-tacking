package org.bustimes.app;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the scheduled times for one stop out of a TransXChange document.
 */
final class TxcTimetable {

    static final int MONDAY = 1;
    static final int TUESDAY = 2;
    static final int WEDNESDAY = 4;
    static final int THURSDAY = 8;
    static final int FRIDAY = 16;
    static final int SATURDAY = 32;
    static final int SUNDAY = 64;
    static final int ALL_DAYS = MONDAY | TUESDAY | WEDNESDAY | THURSDAY | FRIDAY | SATURDAY | SUNDAY;

    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    static final class Departure {
        final String line;
        final String destination;
        final int secondsOfDay;
        final int daysMask;
        final String validFrom;
        final String validTo;

        Departure(String line, String destination, int secondsOfDay, int daysMask,
                String validFrom, String validTo) {
            this.line = line == null ? "" : line;
            this.destination = destination == null ? "" : destination;
            this.secondsOfDay = secondsOfDay;
            this.daysMask = daysMask;
            this.validFrom = validFrom == null ? "" : validFrom;
            this.validTo = validTo == null ? "" : validTo;
        }

        String clock() {
            return formatClock(secondsOfDay);
        }

        boolean runsOn(String isoDate) {
            if (!validFrom.isEmpty() && isoDate.compareTo(validFrom) < 0) {
                return false;
            }
            return validTo.isEmpty() || isoDate.compareTo(validTo) <= 0;
        }
    }

    private TxcTimetable() { }

    static String formatClock(int secondsOfDay) {
        int seconds = ((secondsOfDay % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY;
        return String.format(Locale.UK, "%02d:%02d", seconds / 3600, (seconds % 3600) / 60);
    }

    static int parseClock(String value) {
        if (value == null) {
            return -1;
        }
        String[] parts = value.trim().split(":");
        if (parts.length < 2) {
            return -1;
        }
        try {
            int hours = Integer.parseInt(parts[0].trim());
            int minutes = Integer.parseInt(parts[1].trim());
            int seconds = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            return ((hours * 3600 + minutes * 60 + seconds) % SECONDS_PER_DAY + SECONDS_PER_DAY)
                    % SECONDS_PER_DAY;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int parseDuration(String iso) {
        if (iso == null) {
            return 0;
        }
        String value = iso.trim().toUpperCase(Locale.UK);
        if (value.isEmpty() || value.charAt(0) != 'P') {
            return 0;
        }
        int total = 0;
        int number = 0;
        boolean inTime = false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 'T') {
                inTime = true;
            } else if (Character.isDigit(c)) {
                number = number * 10 + (c - '0');
            } else {
                switch (c) {
                    case 'D': total += number * 86400; break;
                    case 'W': total += number * 7 * 86400; break;
                    case 'H': total += number * 3600; break;
                    case 'M': total += number * (inTime ? 60 : 0); break;
                    case 'S': total += number; break;
                    default: break;
                }
                number = 0;
            }
        }
        return total;
    }

    static int dayMaskForCalendarDay(int calendarDayOfWeek) {
        switch (calendarDayOfWeek) {
            case Calendar.MONDAY: return MONDAY;
            case Calendar.TUESDAY: return TUESDAY;
            case Calendar.WEDNESDAY: return WEDNESDAY;
            case Calendar.THURSDAY: return THURSDAY;
            case Calendar.FRIDAY: return FRIDAY;
            case Calendar.SATURDAY: return SATURDAY;
            case Calendar.SUNDAY: return SUNDAY;
            default: return ALL_DAYS;
        }
    }

    static int maskForDayName(String name) {
        if (name == null) {
            return 0;
        }
        switch (name) {
            case "Monday": return MONDAY;
            case "Tuesday": return TUESDAY;
            case "Wednesday": return WEDNESDAY;
            case "Thursday": return THURSDAY;
            case "Friday": return FRIDAY;
            case "Saturday": return SATURDAY;
            case "Sunday": return SUNDAY;
            case "MondayToFriday": return MONDAY | TUESDAY | WEDNESDAY | THURSDAY | FRIDAY;
            case "MondayToSaturday": return MONDAY | TUESDAY | WEDNESDAY | THURSDAY | FRIDAY | SATURDAY;
            case "MondayToSunday":
            case "AllWeek": return ALL_DAYS;
            case "Weekend": return SATURDAY | SUNDAY;
            case "TuesdayToSaturday":
                return TUESDAY | WEDNESDAY | THURSDAY | FRIDAY | SATURDAY;
            default: return 0;
        }
    }

    static List<Departure> parse(InputStream stream, String atcoCode) throws Exception {
        Collector collector = new Collector(atcoCode == null ? "" : atcoCode.trim());
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(stream, null);
        collector.run(parser);
        return collector.departures();
    }

    static List<Departure> upcoming(List<Departure> departures, int todayMask, String isoDate,
            int fromSeconds, int limit) {
        List<Departure> today = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Departure departure : departures) {
            if ((departure.daysMask & todayMask) == 0 || !departure.runsOn(isoDate)) {
                continue;
            }
            if (departure.secondsOfDay < fromSeconds) {
                continue;
            }
            String key = departure.line + "|" + departure.destination + "|" + departure.secondsOfDay;
            if (seen.add(key)) {
                today.add(departure);
            }
        }
        Collections.sort(today, new Comparator<Departure>() {
            @Override
            public int compare(Departure a, Departure b) {
                if (a.secondsOfDay != b.secondsOfDay) {
                    return Integer.compare(a.secondsOfDay, b.secondsOfDay);
                }
                return a.line.compareToIgnoreCase(b.line);
            }
        });
        if (limit > 0 && today.size() > limit) {
            return new ArrayList<>(today.subList(0, limit));
        }
        return today;
    }

    // ------------------------------------------------------------------ parsing

    private static final class Link {
        final String id;
        final String from;
        final String to;
        final int seconds;

        Link(String id, String from, String to, int seconds) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.seconds = seconds;
        }
    }

    private static final class Pattern {
        final String[] stopRefs;
        final String[] linkIds;
        final int[] linkSeconds;
        final String destination;

        Pattern(String[] stopRefs, String[] linkIds, int[] linkSeconds, String destination) {
            this.stopRefs = stopRefs;
            this.linkIds = linkIds;
            this.linkSeconds = linkSeconds;
            this.destination = destination;
        }

        int indexOf(String stopRef) {
            for (int i = 0; i < stopRefs.length; i++) {
                if (stopRefs[i].equalsIgnoreCase(stopRef)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static final class Journey {
        String code = "";
        String vehicleJourneyRef = "";
        String serviceRef = "";
        String lineRef = "";
        String journeyPatternRef = "";
        int departureSeconds = -1;
        int daysMask;
        Map<String, Integer> runOverrides;

        boolean hasDepartureTime() {
            return departureSeconds >= 0;
        }

        void addOverride(String linkId, int seconds) {
            if (linkId.isEmpty()) {
                return;
            }
            if (runOverrides == null) {
                runOverrides = new HashMap<>();
            }
            runOverrides.put(linkId, seconds);
        }
    }

    private static final class Collector {
        private final String targetStop;
        private final Map<String, List<Link>> sectionLinks = new HashMap<>();
        private final Map<String, Pattern> patterns = new HashMap<>();
        private final Map<String, String> lineNames = new HashMap<>();
        private final Map<String, String> serviceLine = new HashMap<>();
        private final Map<String, String> serviceDestination = new HashMap<>();
        private final Map<String, Integer> serviceDays = new HashMap<>();
        private final Map<String, String[]> serviceValid = new HashMap<>();
        private final List<Journey> journeys = new ArrayList<>();
        private final Map<String, Journey> journeysByCode = new HashMap<>();
        private final List<String> path = new ArrayList<>();

        private String text = "";

        private List<Link> section;
        private String linkId = "";
        private String linkFrom = "";
        private String linkTo = "";
        private int linkSeconds;

        private String service = "";
        private int serviceDayMask;
        private String serviceStart = "";
        private String serviceEnd = "";
        private String lineId = "";
        private String lineName = "";
        private String patternId = "";
        private String patternDestination = "";
        private List<String> patternSections;

        private Journey journey;
        private String overrideLinkId = "";

        Collector(String targetStop) {
            this.targetStop = targetStop;
        }

        void run(XmlPullParser parser) throws Exception {
            int event;
            do {
                event = parser.next();
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    // Fast-skip <RouteSections>: it contains tens of thousands of road coordinates
                    // that are never used for stop timetables.
                    if ("RouteSections".equals(tag)) {
                        skipSubtree(parser);
                        continue;
                    }
                    // If no JourneyPattern in this document visits targetStop, skip VehicleJourneys
                    if ("VehicleJourneys".equals(tag) && patterns.isEmpty()) {
                        return;
                    }
                    path.add(tag);
                    text = "";
                    onStart(parser.getAttributeValue(null, "id"));
                } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                    text = parser.getText();
                } else if (event == XmlPullParser.END_TAG) {
                    onEnd();
                    if (!path.isEmpty()) {
                        path.remove(path.size() - 1);
                    }
                }
            } while (event != XmlPullParser.END_DOCUMENT);
        }

        private static void skipSubtree(XmlPullParser parser) throws Exception {
            int depth = 1;
            while (depth > 0) {
                int ev = parser.next();
                if (ev == XmlPullParser.END_DOCUMENT) {
                    break;
                } else if (ev == XmlPullParser.START_TAG) {
                    depth++;
                } else if (ev == XmlPullParser.END_TAG) {
                    depth--;
                }
            }
        }

        private String leaf() {
            return path.get(path.size() - 1);
        }

        private String parent() {
            return path.size() < 2 ? "" : path.get(path.size() - 2);
        }

        private boolean has(String name) {
            return path.contains(name);
        }

        private void onStart(String id) {
            switch (leaf()) {
                case "JourneyPatternSection":
                    section = new ArrayList<>();
                    sectionLinks.put(id == null ? "" : id, section);
                    break;
                case "JourneyPatternTimingLink":
                    linkId = id == null ? "" : id;
                    linkFrom = "";
                    linkTo = "";
                    linkSeconds = 0;
                    break;
                case "Service":
                    service = "";
                    serviceDayMask = 0;
                    serviceStart = "";
                    serviceEnd = "";
                    break;
                case "Line":
                    lineId = id == null ? "" : id;
                    lineName = "";
                    break;
                case "JourneyPattern":
                    if (has("StandardService")) {
                        patternId = id == null ? "" : id;
                        patternDestination = "";
                        patternSections = new ArrayList<>();
                    }
                    break;
                case "VehicleJourney":
                    journey = new Journey();
                    break;
                case "VehicleJourneyTimingLink":
                    overrideLinkId = "";
                    break;
                default:
                    break;
            }
        }

        private void onEnd() {
            String value = text == null ? "" : text.trim();
            switch (leaf()) {
                case "StopPointRef":
                    if (has("JourneyPatternTimingLink")) {
                        if ("From".equals(parent())) {
                            linkFrom = value;
                        } else if ("To".equals(parent())) {
                            linkTo = value;
                        }
                    }
                    break;
                case "RunTime":
                    if (has("JourneyPatternTimingLink")) {
                        linkSeconds += parseDuration(value);
                    } else if (journey != null && has("VehicleJourneyTimingLink")) {
                        journey.addOverride(overrideLinkId, parseDuration(value));
                    }
                    break;
                case "WaitTime":
                    if (has("JourneyPatternTimingLink")) {
                        linkSeconds += parseDuration(value);
                    }
                    break;
                case "JourneyPatternTimingLink":
                    if ("JourneyPatternSection".equals(parent()) && section != null
                            && !linkFrom.isEmpty() && !linkTo.isEmpty()) {
                        section.add(new Link(linkId, linkFrom, linkTo, linkSeconds));
                    }
                    break;
                case "ServiceCode":
                    if (has("Service")) {
                        service = value;
                    }
                    break;
                case "LineName":
                    if ("Line".equals(parent())) {
                        lineName = value;
                    }
                    break;
                case "Line":
                    if ("Lines".equals(parent()) && !lineId.isEmpty()) {
                        lineNames.put(lineId, lineName);
                        if (!service.isEmpty()) {
                            serviceLine.put(service, lineName);
                        }
                    }
                    break;
                case "StartDate":
                    if (has("OperatingPeriod") && has("Service")) {
                        serviceStart = isoDate(value);
                    }
                    break;
                case "EndDate":
                    if (has("OperatingPeriod") && has("Service")) {
                        serviceEnd = isoDate(value);
                    }
                    break;
                case "Destination":
                    if ("StandardService".equals(parent()) && !service.isEmpty()) {
                        serviceDestination.put(service, tidy(value));
                    }
                    break;
                case "DestinationDisplay":
                    if (has("StandardService")) {
                        patternDestination = tidy(value);
                    }
                    break;
                case "JourneyPatternSectionRefs":
                    if (patternSections != null && has("StandardService")) {
                        patternSections.add(value);
                    }
                    break;
                case "JourneyPattern":
                    if (patternSections != null) {
                        addPattern(patternId, patternSections, patternDestination);
                        patternSections = null;
                    }
                    break;
                case "Service":
                    if ("Services".equals(parent()) && !service.isEmpty()) {
                        serviceDays.put(service, serviceDayMask);
                        serviceValid.put(service, new String[] { serviceStart, serviceEnd });
                    }
                    break;
                case "VehicleJourneyCode":
                    if (journey != null && "VehicleJourney".equals(parent())) {
                        journey.code = value;
                    }
                    break;
                case "VehicleJourneyRef":
                    if (journey != null && "VehicleJourney".equals(parent())) {
                        journey.vehicleJourneyRef = value;
                    }
                    break;
                case "ServiceRef":
                    if (journey != null && "VehicleJourney".equals(parent())) {
                        journey.serviceRef = value;
                    }
                    break;
                case "LineRef":
                    if (journey != null && "VehicleJourney".equals(parent())) {
                        journey.lineRef = value;
                    }
                    break;
                case "JourneyPatternRef":
                    if (journey != null && "VehicleJourney".equals(parent())) {
                        journey.journeyPatternRef = value;
                    }
                    break;
                case "DepartureTime":
                    if (journey != null && "VehicleJourney".equals(parent())) {
                        journey.departureSeconds = parseClock(value);
                    }
                    break;
                case "JourneyPatternTimingLinkRef":
                    if (journey != null && "VehicleJourneyTimingLink".equals(parent())) {
                        overrideLinkId = value;
                    }
                    break;
                case "VehicleJourney":
                    if (journey != null) {
                        if (!journey.code.isEmpty()) {
                            journeysByCode.put(journey.code, journey);
                        }
                        if (journey.hasDepartureTime()) {
                            journeys.add(journey);
                        }
                    }
                    journey = null;
                    break;
                default:
                    applyDayName();
                    break;
            }
        }

        private void applyDayName() {
            if (!"DaysOfWeek".equals(parent()) || has("NonOperation")
                    || has("DaysOfNonOperation")) {
                return;
            }
            int mask = maskForDayName(leaf());
            if (mask == 0) {
                return;
            }
            if (journey != null && has("VehicleJourney")) {
                journey.daysMask |= mask;
            } else if (has("Service")) {
                serviceDayMask |= mask;
            }
        }

        private void addPattern(String id, List<String> sectionRefs, String destination) {
            if (id.isEmpty()) {
                return;
            }
            List<String> stops = new ArrayList<>();
            List<String> linkIds = new ArrayList<>();
            List<Integer> seconds = new ArrayList<>();
            for (String ref : sectionRefs) {
                List<Link> links = sectionLinks.get(ref);
                if (links == null) {
                    continue;
                }
                for (Link link : links) {
                    if (stops.isEmpty()) {
                        stops.add(link.from);
                    }
                    stops.add(link.to);
                    linkIds.add(link.id);
                    seconds.add(link.seconds);
                }
            }
            int index = indexOfStop(stops, targetStop);
            if (index < 0 || stops.size() != linkIds.size() + 1) {
                return;
            }
            int[] runSeconds = new int[seconds.size()];
            for (int i = 0; i < runSeconds.length; i++) {
                runSeconds[i] = seconds.get(i);
            }
            patterns.put(id, new Pattern(stops.toArray(new String[0]),
                    linkIds.toArray(new String[0]), runSeconds, destination));
        }

        private static int indexOfStop(List<String> stops, String target) {
            for (int i = 0; i < stops.size(); i++) {
                if (stops.get(i).equalsIgnoreCase(target)) {
                    return i;
                }
            }
            return -1;
        }

        /** Resolves inherited fields when a VehicleJourney references another via VehicleJourneyRef. */
        private void resolveInheritedFields(Journey j) {
            int hops = 0;
            Journey parent = j;
            while ((j.journeyPatternRef.isEmpty() || j.serviceRef.isEmpty()
                    || j.lineRef.isEmpty() || j.daysMask == 0)
                    && !parent.vehicleJourneyRef.isEmpty() && hops < 8) {
                Journey next = journeysByCode.get(parent.vehicleJourneyRef);
                if (next == null || next == parent) {
                    break;
                }
                if (j.journeyPatternRef.isEmpty()) {
                    j.journeyPatternRef = next.journeyPatternRef;
                }
                if (j.serviceRef.isEmpty()) {
                    j.serviceRef = next.serviceRef;
                }
                if (j.lineRef.isEmpty()) {
                    j.lineRef = next.lineRef;
                }
                if (j.daysMask == 0) {
                    j.daysMask = next.daysMask;
                }
                parent = next;
                hops++;
            }
        }

        private List<Departure> timesAlong(Journey journey, Pattern pattern) {
            String[] valid = serviceValid.get(journey.serviceRef);
            int days = journey.daysMask != 0
                    ? journey.daysMask
                    : maskOrDefault(serviceDays.get(journey.serviceRef));
            String line = firstNonEmpty(lineNames.get(journey.lineRef),
                    serviceLine.get(journey.serviceRef));
            String destination = firstNonEmpty(pattern.destination,
                    serviceDestination.get(journey.serviceRef));

            List<Departure> times = new ArrayList<>();
            int seconds = journey.departureSeconds;
            for (int i = 0; i < pattern.stopRefs.length - 1; i++) {
                if (i > 0) {
                    int run = pattern.linkSeconds[i - 1];
                    if (journey.runOverrides != null) {
                        Integer override = journey.runOverrides.get(pattern.linkIds[i - 1]);
                        if (override != null) {
                            run = override;
                        }
                    }
                    seconds += run;
                }
                times.add(new Departure(line, destination, seconds % SECONDS_PER_DAY, days,
                        valid == null ? "" : valid[0], valid == null ? "" : valid[1]));
            }
            return times;
        }

        List<Departure> departures() {
            List<Departure> result = new ArrayList<>();
            for (Journey journey : journeys) {
                resolveInheritedFields(journey);
                if (journey.journeyPatternRef.isEmpty()) {
                    continue;
                }
                Pattern pattern = patterns.get(journey.journeyPatternRef);
                if (pattern == null) {
                    continue;
                }
                int index = pattern.indexOf(targetStop);
                if (index < 0) {
                    continue;
                }
                List<Departure> times = timesAlong(journey, pattern);
                if (index < times.size()) {
                    result.add(times.get(index));
                }
            }
            return result;
        }

        private static int maskOrDefault(Integer mask) {
            return mask == null || mask == 0 ? ALL_DAYS : mask;
        }
    }

    private static String isoDate(String value) {
        if (value == null || value.length() < 10) {
            return "";
        }
        return value.substring(0, 10);
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static String tidy(String value) {
        return value == null ? "" : value.replace("__", ", ").replace('_', ' ').trim();
    }
}
