package org.bustimes.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * How old a vehicle's last GPS report is.
 * <p>
 * The BODS feed keeps serving a vehicle's most recent report long after the vehicle stopped
 * transmitting, so a record in the feed is not proof the bus is running now. The report's own
 * RecordedAtTime is what says how fresh it really is.
 */
final class FixAge {

    /** Reports older than this are not shown as live buses. */
    static final long MAX_LIVE_AGE_MS = 10L * 60 * 1000;

    private FixAge() { }

    /** Parses an ISO-8601 time with an offset; 0 when missing or unreadable. */
    static long parse(String iso) {
        if (iso == null || iso.isEmpty()) {
            return 0;
        }
        try {
            String cleaned = iso.replaceFirst("\\.\\d+", "");
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.UK);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = format.parse(cleaned);
            return date != null ? date.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** True when the report is recent enough to call the bus live. An unreadable time counts as live. */
    static boolean isLive(String recordedAt, long nowMs) {
        long recorded = parse(recordedAt);
        return recorded <= 0 || nowMs - recorded <= MAX_LIVE_AGE_MS;
    }

    /** Age of the GPS fix in seconds, or {@code fallbackSeconds} when the feed gave no readable time. */
    static long ageSeconds(String recordedAt, long nowMs, long fallbackSeconds) {
        long recorded = parse(recordedAt);
        if (recorded <= 0) {
            return fallbackSeconds;
        }
        return Math.max(0, (nowMs - recorded) / 1000);
    }

    /** "12s ago", "5m ago", "3h ago", "2d ago". */
    static String describe(long seconds) {
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h ago";
        }
        return (seconds / 86400) + "d ago";
    }
}
