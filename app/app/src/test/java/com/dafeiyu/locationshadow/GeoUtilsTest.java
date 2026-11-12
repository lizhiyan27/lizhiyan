package com.dafeiyu.locationshadow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;
import java.util.Random;

public final class GeoUtilsTest {
    private static final double DELTA = 1.0e-10;

    @Test
    public void formatUsesSevenDecimalsAndLocaleIndependentDot() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("116.1234568", GeoUtils.format(116.12345678d));
            assertEquals("-39.0000000", GeoUtils.format(-39.0d));
            assertEquals("0.0000000", GeoUtils.format(0.0d));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void moveFollowsEastNorthWestAndSouthDirections() {
        double lng = 116.397;
        double lat = 39.908;

        double[] east = GeoUtils.move(lng, lat, 10.0, 0.0, 1.0).position;
        assertTrue(east[0] > lng);
        assertEquals(lat, east[1], DELTA);
        assertEquals(lng + 10.0 / (111320.0 * Math.cos(Math.abs(lat) * Math.PI / 180.0)),
                east[0], DELTA);

        double[] north = GeoUtils.move(lng, lat, 0.0, 10.0, 1.0).position;
        assertEquals(lng, north[0], DELTA);
        assertTrue(north[1] > lat);
        assertEquals(lat + 10.0 / 110574.0, north[1], DELTA);

        double[] west = GeoUtils.move(lng, lat, -10.0, 0.0, 1.0).position;
        assertTrue(west[0] < lng);
        assertEquals(lat, west[1], DELTA);

        double[] south = GeoUtils.move(lng, lat, 0.0, -10.0, 1.0).position;
        assertEquals(lng, south[0], DELTA);
        assertTrue(south[1] < lat);
    }

    @Test
    public void moveClampsLatitudeAndWrapsLongitudeAtSupportedBoundaries() {
        double[] north = GeoUtils.move(0.0, 85.0, 0.0, 1.0e9, 1.0).position;
        assertEquals(90.0, north[1], 0.0);
        double[] south = GeoUtils.move(0.0, -85.0, 0.0, -1.0e9, 1.0).position;
        assertEquals(-90.0, south[1], 0.0);

        double startLng = 179.999999;
        double[] acrossDateLine = GeoUtils.move(startLng, 0.0, 10.0, 0.0, 1.0).position;
        double expected = startLng + 10.0 / 111320.0 - 360.0;
        assertEquals(expected, acrossDateLine[0], DELTA);
        assertTrue(acrossDateLine[0] < -179.999);

        assertEquals(180.0, GeoUtils.clamp(181.0, -180.0, 180.0), 0.0);
        assertEquals(-90.0, GeoUtils.clamp(-91.0, -90.0, 90.0), 0.0);
    }

    @Test
    public void bd09ConversionOutsideChinaDoesNotApplyGcj02Offset() {
        double[] bd = GeoUtils.wgs84ToBd09(-73.9857, 40.7484);
        assertEquals(-73.97907569950418, bd[0], 1.0e-9);
        assertEquals(40.754608036681965, bd[1], 1.0e-9);
        double[] wgs = GeoUtils.bd09ToWgs84(bd[0], bd[1]);
        assertEquals(-73.9857, wgs[0], 1.0e-6);
        assertEquals(40.7484, wgs[1], 1.0e-6);
    }

    @Test
    public void gcjConversionRoundTripInsideChinaStaysCloseToOriginal() {
        double lng = 116.397;
        double lat = 39.908;
        double[] gcj = GeoUtils.wgs84ToGcj02(lng, lat);
        double[] wgs = GeoUtils.gcj02ToWgs84(gcj[0], gcj[1]);
        assertEquals(lng, wgs[0], 1.0e-5);
        assertEquals(lat, wgs[1], 1.0e-5);
        double[] outside = GeoUtils.wgs84ToGcj02(-73.9857, 40.7484);
        assertEquals(-73.9857, outside[0], 0.0);
        assertEquals(40.7484, outside[1], 0.0);
    }

    @Test
    public void randomOffsetNeverExceedsConfiguredMeterBounds() {
        final double lng = 116.397;
        final double lat = 39.908;
        final double maxLngMeters = 25.0;
        final double maxLatMeters = 40.0;
        Random random = new Random(20261111L);

        for (int index = 0; index < 500; index++) {
            double[] shifted = GeoUtils.randomOffset(lng, lat,
                    maxLngMeters, maxLatMeters, random);
            double eastMeters = (shifted[0] - lng) * 111320.0
                    * Math.max(0.01, Math.cos(Math.abs(lat) * Math.PI / 180.0));
            double northMeters = (shifted[1] - lat) * 110574.0;
            assertTrue("east offset exceeded maximum", Math.abs(eastMeters) <= maxLngMeters + DELTA);
            assertTrue("north offset exceeded maximum", Math.abs(northMeters) <= maxLatMeters + DELTA);
        }
    }

    @Test
    public void randomOffsetWithZeroBoundsReturnsOriginalCoordinates() {
        double[] shifted = GeoUtils.randomOffset(117.2, 36.7,
                0.0, 0.0, new Random(7L));
        assertEquals(117.2, shifted[0], 0.0);
        assertEquals(36.7, shifted[1], 0.0);
    }
}
