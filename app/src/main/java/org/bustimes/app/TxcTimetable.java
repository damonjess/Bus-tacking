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
 *
 * This is the timetables half of the DfT Bus Open Data Service. The live SIRI-VM feed says where
 * vehicles are now and carries no timetable, so a stop's scheduled departures can only come from
 * the TransXChange documents the operators publish.
 *
 * The document is streamed once, and only journey patterns that actually call at the requested stop
 * are kept, because one operator's file can cover hundreds of services. Vehicle journeys are
 * resolved after the whole document has been read, so the result does not depend on the order the
 * file happens to use for its sections.
 *
 * Kept free of Android types so the rules can be unit tested on the JVM.
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

    /** One scheduled departure from a stop. */
    static final class Departure {
        final String line;
        final String destination;
        /** Seconds after midnight. */
        final int secondsOfDay;
        final int daysMask;
        /** ISO date bounds from the service's operating period, or empty when unbounded. */
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

        /** "13:52". */
        String clock() {
            return formatClock(secondsOfDay);
        }

        /** Whether the service's operating period covers this ISO date. */
        boolean runsOn(String isoDate) {
            if (!validFrom.isEmpty() && isoDate.compareTo(validFrom) < 0) {
                return false;
            }
            return validTo.isEmpty() || isoDate.compareTo(validTo) <= 0;
        }
    }

    private TxcTimetable() { }

    /** Formats seconds after midnight as a 24-hour clock time. */
    static String formatClock(int secondsOfDay) {
        int seconds = ((secondsOfDay % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY;
        return String.format(Locale.UK, "%02d:%02d", seconds / 3600, (seconds % 3600) / 60);
    }

    /** Turns {@code 07:35:00} (or {@code 24:10:00}) into seconds after midnight; -1 when unreadable. */
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

    /** {@code PT1H2M30S} (and {@code P1DT2H}) in seconds; 0 when unreadable. */
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
                    // Before the T these are months, which no bus timetable uses; after it, minutes.
                    case 'M': total += number * (inTime ? 60 : 0); break;
                    case 'S': total += number; break;
                    default: break;
                }
                number = 0;
            }
        }
        return total;
    }

    /** Day mask for a {@link Calendar} day-of-week constant. */
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

    /** The days a TransXChange day name covers, or 0 when it is not a weekday rule. */
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
            case "MondayToSunday": return ALL_DAYS;
            case "Weekend": return SATURDAY | SUNDAY;
            case "TuesdayToSaturday":
                return TUESDAY | WEDNESDAY | THURSDAY | FRIDAY | SATURDAY;
            default: return 0;
        }
    }

    /**
     * The departures this document schedules from {@code atcoCode}, with no date or day filtering.
     */
    static List<Departure> parse(InputStream stream, String atcoCode) throws Exception {
        Collector collector = new Collector(atcoCode == null ? "" : atcoCode.trim());
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(stream, null);
        collector.run(parser);
        return collector.departures();
    }

    /**
     * The remaining departures for one day: on that weekday, inside the service's operating dates,
     * at or after {@code fromSeconds}, earliest first, with duplicates collapsed.
     */
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

    /** One hop of a journey pattern: from one stop to the next, with the time it takes. */
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

    /** A route a vehicle journey follows: its stops, and the time between them. */
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
                if (stopRefs[i].equals(stopRef)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** One scheduled run, before its stop times are worked out. */
    private static final class Journey {
        String serviceRef = "";
        String lineRef = "";
        String journeyPatternRef = "";
        int departureSeconds = -1;
        int daysMask;
        Map<String, Integer> runOverrides;

        boolean isUsable() {
            return departureSeconds >= 0 && !journeyPatternRef.isEmpty();
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
                    path.add(parser.getName());
                    text = "";
                    onStart(parser.getAttributeValue(null, "id"));
                } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                    text = parser.getText();
                } else if (event == XmlPullParser.END_TAG) {
                    onEnd();
                    path.remove(path.size() - 1);
                }
            } while (event != XmlPullParser.END_DOCUMENT);
        }

        private String leaf() {
            return path.get(path.size() - 1);
        }

        /** The element the current one sits inside. */
        private String parent() {
            return path.size() < 2 ? "" : path.get(path.size() - 2);
        }

        /** True when the element or any of its ancestors has this name. */
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
                    } else if (has("VehicleJourneyTimingLink")) {
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
                    if (journey != null && journey.isUsable()) {
                        journeys.add(journey);
                    }
                    journey = null;
                    break;
                default:
                    applyDayName();
                    break;
            }
        }

        /** Day rules appear as elements, not text: {@code <DaysOfWeek><MondayToFriday/></DaysOfWeek>}. */
        private void applyDayName() {
            if (!"DaysOfWeek".equals(parent())) {
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
            int index = stops.indexOf(targetStop);
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

        /** The times this journey gives each stop of its pattern, in pattern order. */
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
            // The last stop of a pattern is where the bus ends up, not a departure from it.
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

    /** {@code Brigg__Cary_Lane} reads as "Brigg, Cary Lane" in the published data. */
    private static String tidy(String value) {
        return value == null ? "" : value.replace("__", ", ").replace('_', ' ').trim();
    }
}
