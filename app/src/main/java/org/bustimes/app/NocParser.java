package org.bustimes.app;

import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads operator code to public name pairs out of Traveline's National Operator Codes (NOC) XML.
 *
 * Free of Android types so it can be unit tested on the JVM. It does not rely on the exact table
 * layout: any element that has both a NOCCODE child and a public-name child (in either order) is one
 * pair. Records with no code or no name are skipped, so unrelated tables in the same file are ignored.
 */
final class NocParser {

    /** Name fields in order of preference. Lower-case. */
    private static final String[] NAME_TAGS = {
            "operatorpublicname", "pubnm", "publicname", "referencename", "refnm", "operatorname"};

    private NocParser() { }

    private static boolean isNameTag(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        for (String candidate : NAME_TAGS) {
            if (candidate.equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static String bestName(Map<String, String> names) {
        for (String candidate : NAME_TAGS) {
            String value = names.get(candidate);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Fills {@code out} with code to name and returns how many pairs were found. */
    static int parse(XmlPullParser parser, Map<String, String> out) throws Exception {
        int count = 0;
        int depth = 0;
        int parentDepth = -1;
        String code = null;
        Map<String, String> names = new HashMap<>();
        StringBuilder text = new StringBuilder();

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                depth++;
                text.setLength(0);
            } else if (event == XmlPullParser.TEXT) {
                text.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                String value = text.toString().trim();
                if ("NOCCODE".equalsIgnoreCase(tag) && !value.isEmpty()) {
                    code = value;
                    parentDepth = depth - 1;
                } else if (isNameTag(tag) && !value.isEmpty()) {
                    names.put(tag.toLowerCase(Locale.ROOT), value);
                    parentDepth = depth - 1;
                }
                if (depth == parentDepth) {
                    String name = bestName(names);
                    if (code != null && name != null && !out.containsKey(code)) {
                        out.put(code, name);
                        count++;
                    }
                    code = null;
                    names.clear();
                    parentDepth = -1;
                }
                depth--;
                text.setLength(0);
            }
            event = parser.next();
        }
        return count;
    }
}
