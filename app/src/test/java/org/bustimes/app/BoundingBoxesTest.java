package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BoundingBoxesTest {

    @Test
    public void realViewportIsUsable() {
        assertTrue(BoundingBoxes.isUsable("-0.8000,53.5000,-0.5000,53.6600"));
        assertTrue(BoundingBoxes.isUsable("-0.8500,53.4800,-0.4000,53.7500"));
    }

    @Test
    public void zeroSizeViewportIsRejected() {
        // what a MapView reports before it has been laid out
        assertFalse(BoundingBoxes.isUsable("-0.6548,53.5786,-0.6548,53.5786"));
        assertFalse(BoundingBoxes.isUsable("0,0,0,0"));
        assertFalse(BoundingBoxes.isUsable("-0.6548,53.5786,-0.6548,53.6000"));
        assertFalse(BoundingBoxes.isUsable("-0.6600,53.5786,-0.6548,53.5786"));
    }

    @Test
    public void missingOrMalformedBoxIsRejected() {
        assertFalse(BoundingBoxes.isUsable(""));
        assertFalse(BoundingBoxes.isUsable("   "));
        assertFalse(BoundingBoxes.isUsable(null));
        assertFalse(BoundingBoxes.isUsable("-0.8,53.5,-0.5"));
        assertFalse(BoundingBoxes.isUsable("-0.8,53.5,-0.5,53.6,1"));
        assertFalse(BoundingBoxes.isUsable("-0.8,north,-0.5,53.6"));
    }

    @Test
    public void southOfNorthIsRejected() {
        assertFalse(BoundingBoxes.isUsable("-0.8000,53.6600,-0.5000,53.5000"));
    }

    @Test
    public void parsesInFeedOrder() {
        double[] box = BoundingBoxes.parse("-0.8000,53.5000,-0.5000,53.6600");
        assertNotNull(box);
        assertEquals(-0.8, box[0], 0.00001);
        assertEquals(53.5, box[1], 0.00001);
        assertEquals(-0.5, box[2], 0.00001);
        assertEquals(53.66, box[3], 0.00001);
        assertNull(BoundingBoxes.parse("0,0,0,0"));
    }
}
