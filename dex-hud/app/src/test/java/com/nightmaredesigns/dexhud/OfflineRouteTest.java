package com.nightmaredesigns.dexhud;

/** Dependency-free JVM regression checks; run main with the JDK (no Android device required). */
public final class OfflineRouteTest {
    private static int assertions;

    public static void main(String[] args) {
        parser();
        geometry();
        arrival();
        System.out.println("OfflineRouteTest: " + assertions + " checks passed");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(String text, String error) {
        try { OfflineRoute.parse(text); throw new AssertionError("Accepted invalid input"); }
        catch (IllegalArgumentException exception) {
            check(exception.getMessage().contains(error), "Explicit error: " + error);
        }
    }

    private static void parser() {
        OfflineRoute route = OfflineRoute.parse(" \r\n +51.5, -0.12, Café \r\n.5,1e2\n-90,180,\n90,-180,North");
        check(route.points.size() == 4, "Ordered points / blank lines");
        check(route.points.get(0).label.equals("Café"), "Unicode label");
        check(route.points.get(1).longitude == 100, "Decimal / exponent");
        check(route.points.get(2).label.equals("Waypoint 3"), "Default label");
        check(OfflineRoute.parse(route.editableText()).points.size() == 4, "Editable round trip");
        check(OfflineRoute.parse("0,0").points.size() == 1, "Direct single point");
        reject("", "at least one");
        reject(null, "at least one");
        reject(" \n ", "at least one");
        reject("0", "Line 1");
        reject("0,0,a,b", "latitude,longitude");
        reject("NaN,0", "finite");
        reject("0,Infinity", "finite");
        reject("1e999,0", "between");
        reject("90.0001,0", "latitude");
        reject("0,-180.0001", "longitude");
        reject("0x1.0p0,0", "finite");
        reject("1f,0", "finite");
        reject("0,,label", "longitude");
        reject("0,0," + "a".repeat(81), "80 characters");
        reject("0,0,a\u0001b", "control character");
        reject("0,0\nbad,0", "Line 2");
        check(OfflineRoute.parse("0,0\n".repeat(100)).points.size() == 100, "100 points allowed");
        reject("0,0\n".repeat(101), "maximum 100");
        check(OfflineRoute.parse("0,0" + " ".repeat(19997)).points.size() == 1, "20k allowed");
        reject("0,0" + " ".repeat(19998), "20,000");
        try {
            route.points.clear();
            throw new AssertionError("Mutable route");
        } catch (UnsupportedOperationException expected) { check(true, "Immutable list"); }
    }

    private static void geometry() {
        OfflineRoute route = OfflineRoute.parse("0,179.9,West\n0,-179.9,East");
        double distance = OfflineRoute.distance(0, 179.9, route.points.get(1));
        check(distance > 22000 && distance < 23000, "Short date-line crossing");
        check(Math.abs(OfflineRoute.bearing(0, 179.9, route.points.get(1)) - 90) < .001, "Date-line east bearing");
        double[][] sketch = route.sketch();
        check(Math.abs(sketch[1][0] - sketch[0][0]) < .21, "Unwrapped sketch");
        OfflineRoute duplicates = OfflineRoute.parse("0,0,First\n0,0,Second\n0,0,Third");
        double[][] duplicateSketch = duplicates.sketch();
        check(duplicateSketch.length == 3, "Duplicates retain supplied order");
        for (double[] point : duplicateSketch) {
            check(Double.isFinite(point[0]) && Double.isFinite(point[1]), "Finite coincident sketch");
            check(point[0] == duplicateSketch[0][0] && point[1] == duplicateSketch[0][1], "Coincident positions");
        }
        OfflineRoute crossings = OfflineRoute.parse("0,179.9\n0,-179.9\n0,-179.9\n0,179.8");
        double[][] crossingSketch = crossings.sketch();
        for (int i = 1; i < crossingSketch.length; i++) {
            check(Math.abs(crossingSketch[i][0] - crossingSketch[i - 1][0]) < .31,
                    "Repeated date-line crossings / duplicates remain short");
        }
        OfflineRoute polar = OfflineRoute.parse("90,180\n90,-180\n-90,0");
        for (double[] point : polar.sketch()) {
            check(Double.isFinite(point[0]) && Double.isFinite(point[1]), "Finite polar sketch");
        }
        check(Double.isFinite(OfflineRoute.distance(90, 180, polar.points.get(2))), "Finite antipodal distance");
        check(OfflineRoute.distance(90, 180, polar.points.get(0)) == 0, "Coincident distance");
        check(OfflineRoute.cardinal(-90).equals("West"), "West");
        check(OfflineRoute.cardinal(90).equals("East"), "East");
        check(OfflineRoute.cardinal(359).equals("North"), "North wrap");
        check(OfflineRoute.cardinal(180).equals("South"), "South");
        check(OfflineRoute.cardinal(45).equals("North East"), "Intercardinal");
    }

    private static void arrival() {
        OfflineRoute.ArrivalGate gate = new OfflineRoute.ArrivalGate();
        check(!gate.update(false, true, true, 1000, 1000, 3, 5), "First walking fix");
        check(!gate.update(false, true, true, 2000, 2000, 3, 5), "Second walking fix");
        check(!gate.update(false, true, true, 3000, 3000, 3, 5), "Walking dwell insufficient");
        check(gate.update(false, true, true, 4000, 4000, 3, 5), "Walking dwell reached");
        gate.reset();
        check(!gate.update(true, true, true, 1000, 1000, 15, 10), "First driving fix");
        check(!gate.update(true, true, true, 2000, 2000, 15, 10), "Second driving fix");
        check(gate.update(true, true, true, 3000, 3000, 15, 10), "Driving dwell reached");
        gate.reset();
        check(!gate.update(false, true, true, 1000, 1000, 3, 5), "New target dwell resets");
        for (int i = 0; i < 10; i++) check(!gate.update(false, true, true, 5000, 1000, 3, 5), "Duplicate fix never arrives");
        check(!gate.update(false, true, true, 5000, 500, 3, 5), "Out-of-order fix never arrives");
        check(!gate.update(false, false, true, 5000, 5000, 3, 5), "Approximate permission resets dwell");
        check(!gate.update(false, true, true, 6000, 6000, 3, 5), "After coarse reset");
        check(!gate.update(false, true, false, 7000, 7000, 3, 5), "Non-GPS resets dwell");
        check(!gate.update(false, true, true, 20000, 7000, 3, 5), "Stale GPS resets dwell");
        check(!gate.update(false, true, true, 7000, 8000, 3, 5), "Future fix");
        check(!gate.update(false, true, true, 8000, 8000, 13, 0), "Walking accuracy threshold");
        check(!gate.update(true, true, true, 9000, 9000, 21, 0), "Driving accuracy threshold");
        check(!gate.update(false, true, true, 10000, 10000, 10, 6), "Accuracy plus distance outside walking radius");
        check(!gate.update(true, true, true, 11000, 11000, 20, 11), "Accuracy plus distance outside driving radius");
        check(!gate.update(true, true, true, 12000, 12000, Double.NaN, 0), "Invalid accuracy");
        check(!gate.update(true, true, true, 13000, 13000, 0, Double.POSITIVE_INFINITY), "Invalid distance");
        check(!gate.update(true, true, true, 14000, 14000, -1, 0), "Negative accuracy");
        gate.reset();
        gate.update(false, true, true, 1000, 1000, 1, 1);
        check(!gate.update(false, true, true, 10000, 10000, 1, 1), "Gap resets consecutive dwell");
        check(!gate.update(false, true, true, 11000, 11000, 1, 1), "Gap needs new fixes");
        check(!OfflineRoute.fresh(10000, 1000), "Freshness expiry");
        check(!OfflineRoute.fresh(10000, 0), "Unknown timestamp");
        check(!OfflineRoute.fresh(10000, 11000), "Future timestamp");
        check(OfflineRoute.fresh(10000, 2000), "Freshness boundary");
    }
}
