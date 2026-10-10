package org.bustimes.app;

/**
 * Geometry for the AR view: turns "a bus 300 m north-east of me" into a pixel on the camera preview.
 *
 * Pure Java (no Android types) so it can be unit tested. The device is assumed to be held in portrait,
 * which is how ArActivity is locked.
 *
 * Rotation matrices are Android's {@code SensorManager.getRotationMatrixFromVector} output: a row-major
 * 3x3 that maps device axes (x right, y up the screen, z out of the screen) to world axes
 * (X east, Y north, Z up). The back camera looks along device -z.
 */
final class ArMath {

    static final double EARTH_RADIUS_M = 6_371_000d;

    private ArMath() { }

    /** Metres east and north of (lat0, lon0) to (lat, lon). Accurate enough for a few kilometres. */
    static double[] enu(double lat0, double lon0, double lat, double lon) {
        double north = Math.toRadians(lat - lat0) * EARTH_RADIUS_M;
        double east = Math.toRadians(lon - lon0)
                * Math.cos(Math.toRadians((lat + lat0) / 2d)) * EARTH_RADIUS_M;
        return new double[] {east, north};
    }

    /** Compass bearing in degrees (0 = north, clockwise) of an east/north offset. */
    static double bearingDeg(double east, double north) {
        return normalize360(Math.toDegrees(Math.atan2(east, north)));
    }

    static double normalize360(double degrees) {
        double d = degrees % 360d;
        return d < 0 ? d + 360d : d;
    }

    /** Smallest signed angle in (-180, 180] that turns {@code from} into {@code to}. */
    static double signedAngle(double from, double to) {
        double d = normalize360(to - from);
        return d > 180d ? d - 360d : d;
    }

    /** True-north heading of the back camera, in degrees. */
    static double headingDeg(float[] r, double declinationDeg) {
        double magnetic = Math.toDegrees(Math.atan2(-r[2], -r[5]));
        return normalize360(magnetic + declinationDeg);
    }

    /** True-north heading of the back camera including aim offset, in degrees. */
    static double headingDeg(float[] r, double declinationDeg, double aimOffsetDeg) {
        return headingDeg(r, declinationDeg + aimOffsetDeg);
    }

    /** Cardinal direction string for a heading in degrees (N, NE, E, SE, S, SW, W, NW). */
    static String cardinal(double headingDeg) {
        double norm = normalize360(headingDeg);
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.floor((norm + 22.5) / 45.0) % 8;
        return directions[index];
    }

    /** tan(half horizontal fov) from tan(half vertical fov) and the preview's aspect ratio. */
    static double tanHalfHorizontal(double tanHalfVertical, int width, int height) {
        return tanHalfVertical * width / (double) height;
    }

    /**
     * Pixel position of a point, or null when it is behind the camera or far outside the view.
     *
     * @param east           metres east of the user (true-north frame)
     * @param north          metres north of the user
     * @param up             metres above the camera (negative for things below eye level)
     * @param declinationDeg magnetic declination: the sensors report relative to magnetic north
     */
    static float[] project(float[] r, double east, double north, double up, double declinationDeg,
                           double tanHalfH, double tanHalfV, int width, int height) {
        return project(r, east, north, up, declinationDeg, 0.0, tanHalfH, tanHalfV, width, height);
    }

    /**
     * Pixel position of a point with aim offset in degrees.
     */
    static float[] project(float[] r, double east, double north, double up, double declinationDeg,
                           double aimOffsetDeg, double tanHalfH, double tanHalfV, int width, int height) {
        double decl = Math.toRadians(declinationDeg + aimOffsetDeg);
        // true-north frame -> magnetic-north frame: a target's bearing becomes (bearing - declination)
        double e = east * Math.cos(decl) - north * Math.sin(decl);
        double n = north * Math.cos(decl) + east * Math.sin(decl);
        double length = Math.sqrt(e * e + n * n + up * up);
        if (length < 1e-6) {
            return null;
        }
        e /= length;
        n /= length;
        double h = up / length;

        double xc = e * r[0] + n * r[3] + h * r[6];       // along the camera's right axis
        double yc = e * r[1] + n * r[4] + h * r[7];       // along the camera's up axis
        double zc = -(e * r[2] + n * r[5] + h * r[8]);    // along the camera's viewing direction
        if (zc <= 0.05d) {
            return null;
        }
        double nx = (xc / zc) / tanHalfH;
        double ny = (yc / zc) / tanHalfV;
        if (Math.abs(nx) > 1.6d || Math.abs(ny) > 1.6d) {
            return null;
        }
        return new float[] {
                (float) (width / 2d + nx * width / 2d),
                (float) (height / 2d - ny * height / 2d)};
    }

    /** Exponential smoothing of a rotation matrix. {@code prev} may be null on the first sample. */
    static float[] smooth(float[] prev, float[] next, float alpha) {
        if (prev == null) {
            return next.clone();
        }
        float[] out = new float[9];
        for (int i = 0; i < 9; i++) {
            out[i] = prev[i] + alpha * (next[i] - prev[i]);
        }
        return out;
    }
}
