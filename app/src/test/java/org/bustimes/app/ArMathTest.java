package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArMathTest {

    private static final int W = 1080;
    private static final int H = 2400;
    private static final double TAN_V = Math.tan(Math.toRadians(65d / 2d));
    private static final double TAN_H = ArMath.tanHalfHorizontal(TAN_V, W, H);

    /** Phone upright in portrait, back camera looking due north. */
    private static final float[] FACING_NORTH = {1, 0, 0, 0, 0, -1, 0, 1, 0};
    /** Same, looking due east. */
    private static final float[] FACING_EAST = {0, 0, -1, -1, 0, 0, 0, 1, 0};

    private static float[] project(float[] r, double east, double north, double decl) {
        return ArMath.project(r, east, north, -1.5d, decl, TAN_H, TAN_V, W, H);
    }

    @Test
    public void targetStraightAheadIsHorizontallyCentred() {
        float[] p = project(FACING_NORTH, 0, 100, 0);
        assertNotNull(p);
        assertEquals(W / 2f, p[0], 0.5f);
        assertTrue("ground-level target sits just below centre", p[1] > H / 2f && p[1] < H / 2f + 40f);
    }

    @Test
    public void targetBesideOrBehindIsNotShown() {
        assertNull(project(FACING_NORTH, 100, 0, 0));
        assertNull(project(FACING_NORTH, 0, -100, 0));
    }

    @Test
    public void targetTenDegreesRightLandsRightOfCentre() {
        double b = Math.toRadians(10);
        float[] p = project(FACING_NORTH, Math.sin(b) * 100, Math.cos(b) * 100, 0);
        assertNotNull(p);
        double expected = W / 2d + (Math.tan(b) / TAN_H) * W / 2d;
        assertEquals(expected, p[0], 1.0);
    }

    @Test
    public void declinationShiftsTheTrueNorthFrame() {
        // facing magnetic north with +10 deg declination means looking at true bearing 10
        double b = Math.toRadians(10);
        float[] p = project(FACING_NORTH, Math.sin(b) * 100, Math.cos(b) * 100, 10);
        assertNotNull(p);
        assertEquals(W / 2f, p[0], 0.5f);
    }

    @Test
    public void headingFollowsTheRotationMatrix() {
        assertEquals(0d, ArMath.headingDeg(FACING_NORTH, 0), 0.01);
        assertEquals(90d, ArMath.headingDeg(FACING_EAST, 0), 0.01);
        assertEquals(100d, ArMath.headingDeg(FACING_EAST, 10), 0.01);
    }

    @Test
    public void enuAndBearingAgreeWithCompass() {
        double[] northOffset = ArMath.enu(53.5, -0.6, 53.501, -0.6);
        assertEquals(111.19, northOffset[1], 0.5);
        assertEquals(0d, northOffset[0], 0.01);
        assertEquals(90d, ArMath.bearingDeg(50, 0), 0.01);
        assertEquals(225d, ArMath.bearingDeg(-10, -10), 0.01);
    }

    @Test
    public void signedAngleTakesTheShortWay() {
        assertEquals(20d, ArMath.signedAngle(350, 10), 1e-9);
        assertEquals(-20d, ArMath.signedAngle(10, 350), 1e-9);
        assertEquals(180d, ArMath.signedAngle(0, 180), 1e-9);
    }
}
