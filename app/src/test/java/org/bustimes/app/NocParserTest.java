package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

public class NocParserTest {

    private static Map<String, String> parse(String xml, int[] count) throws Exception {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new StringReader(xml));
        Map<String, String> out = new HashMap<>();
        count[0] = NocParser.parse(parser, out);
        return out;
    }

    @Test
    public void readsCodeAndPublicNameInEitherOrder() throws Exception {
        String xml = "<travelinedata><NOCTable>"
                + "<NOCTableRecord><NOCCODE>AAAA</NOCCODE><OperatorPublicName>Alpha Buses</OperatorPublicName></NOCTableRecord>"
                + "<NOCTableRecord><OperatorPublicName>Beta Travel</OperatorPublicName><NOCCODE>BBBB</NOCCODE></NOCTableRecord>"
                + "</NOCTable></travelinedata>";
        int[] count = new int[1];
        Map<String, String> out = parse(xml, count);
        assertEquals(2, count[0]);
        assertEquals("Alpha Buses", out.get("AAAA"));
        assertEquals("Beta Travel", out.get("BBBB"));
    }

    @Test
    public void ignoresRecordsWithoutBothFields() throws Exception {
        String xml = "<t><PublicName><OperatorPublicName>Orphan Name</OperatorPublicName></PublicName>"
                + "<NOCLines><NOCCODE>CCCC</NOCCODE><Mode>Bus</Mode></NOCLines>"
                + "<NOCTable><NOCTableRecord><NOCCODE>DDDD</NOCCODE><OperatorPublicName>Delta</OperatorPublicName></NOCTableRecord></NOCTable></t>";
        int[] count = new int[1];
        Map<String, String> out = parse(xml, count);
        assertEquals(1, count[0]);
        assertEquals("Delta", out.get("DDDD"));
        assertFalse(out.containsKey("CCCC"));
    }

    @Test
    public void orphanNameDoesNotLeakIntoNextRecord() throws Exception {
        String xml = "<t><PublicName><OperatorPublicName>Orphan</OperatorPublicName></PublicName>"
                + "<NOCLines><NOCCODE>EEEE</NOCCODE></NOCLines></t>";
        int[] count = new int[1];
        Map<String, String> out = parse(xml, count);
        assertEquals(0, count[0]);
        assertFalse(out.containsKey("EEEE"));
    }

    @Test
    public void prefersPublicNameOverReferenceName() throws Exception {
        String xml = "<t><R><NOCCODE>FFFF</NOCCODE><ReferenceName>Legal Ltd</ReferenceName>"
                + "<OperatorPublicName>Friendly Name</OperatorPublicName></R></t>";
        int[] count = new int[1];
        assertEquals("Friendly Name", parse(xml, count).get("FFFF"));
    }

    @Test
    public void firstRecordForACodeWins() throws Exception {
        String xml = "<t><R><NOCCODE>GGGG</NOCCODE><OperatorPublicName>First</OperatorPublicName></R>"
                + "<R><NOCCODE>GGGG</NOCCODE><OperatorPublicName>Second</OperatorPublicName></R></t>";
        int[] count = new int[1];
        assertEquals("First", parse(xml, count).get("GGGG"));
        assertEquals(1, count[0]);
    }
}
