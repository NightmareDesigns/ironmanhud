package com.nightmaredesigns.dexhud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** A supplied path, never a street-routing engine. Contains no Android, storage or network code. */
final class OfflineRoute {
    static final int MAX_TEXT = 20000, MAX_POINTS = 100;
    static final long FRESH_MS = 8000;
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");
    final List<Point> points;

    static final class Point {
        final double latitude, longitude;
        final String label;
        Point(double latitude, double longitude, String label) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.label = label;
        }
    }

    private OfflineRoute(List<Point> points) {
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
    }

    static OfflineRoute parse(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Enter at least one waypoint.");
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("Route exceeds 20,000 characters.");
        ArrayList<Point> result = new ArrayList<>();
        String[] lines = text.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String prefix = "Line " + (i + 1) + ": ";
            if (result.size() == MAX_POINTS) throw new IllegalArgumentException(prefix + "maximum 100 waypoints.");
            String[] fields = line.split(",", -1);
            if (fields.length < 2 || fields.length > 3) {
                throw new IllegalArgumentException(prefix + "use latitude,longitude[,label].");
            }
            double latitude = coordinate(fields[0], 90, prefix + "latitude");
            double longitude = coordinate(fields[1], 180, prefix + "longitude");
            String label = fields.length == 3 ? fields[2].trim() : "";
            if (label.length() > 80) throw new IllegalArgumentException(prefix + "label exceeds 80 characters.");
            for (int c = 0; c < label.length(); c++) {
                if (Character.isISOControl(label.charAt(c))) {
                    throw new IllegalArgumentException(prefix + "label contains a control character.");
                }
            }
            result.add(new Point(latitude, longitude, label.isEmpty() ? "Waypoint " + (result.size() + 1) : label));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Enter at least one waypoint.");
        return new OfflineRoute(result);
    }

    private static double coordinate(String text, int bound, String name) {
        String number = text.trim();
        if (!NUMBER.matcher(number).matches()) throw new IllegalArgumentException(name + " must be a finite decimal number.");
        double value;
        try { value = Double.parseDouble(number); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " is invalid."); }
        if (!Double.isFinite(value) || Math.abs(value) > bound) {
            throw new IllegalArgumentException(name + " must be between -" + bound + " and " + bound + ".");
        }
        return value;
    }

    String editableText() {
        StringBuilder text = new StringBuilder();
        for (Point point : points) {
            text.append(point.latitude).append(',').append(point.longitude).append(',')
                    .append(point.label).append('\n');
        }
        return text.toString();
    }

    static double longitudeDelta(double degrees) {
        return ((degrees + 180) % 360 + 360) % 360 - 180;
    }

    static double distance(double latitude, double longitude, Point point) {
        double a = Math.toRadians(latitude), b = Math.toRadians(point.latitude);
        double lat = b - a, lon = Math.toRadians(longitudeDelta(point.longitude - longitude));
        double haversine = Math.sin(lat / 2) * Math.sin(lat / 2)
                + Math.cos(a) * Math.cos(b) * Math.sin(lon / 2) * Math.sin(lon / 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, haversine))));
    }

    static double bearing(double latitude, double longitude, Point point) {
        double a = Math.toRadians(latitude), b = Math.toRadians(point.latitude);
        double lon = Math.toRadians(longitudeDelta(point.longitude - longitude));
        return normalize(Math.toDegrees(Math.atan2(Math.sin(lon) * Math.cos(b),
                Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(lon))));
    }

    static double normalize(double degrees) { return (degrees % 360 + 360) % 360; }

    static String cardinal(double degrees) {
        String[] names = {"North", "North East", "East", "South East", "South", "South West", "West", "North West"};
        return names[(int) Math.floor((normalize(degrees) + 22.5) / 45) % 8];
    }

    /** Unwrap each segment across the date line; this is a schematic, not a map projection. */
    double[][] sketch() {
        double[][] positions = new double[points.size()][2];
        double longitude = points.get(0).longitude;
        double scale = Math.max(.01, Math.cos(Math.toRadians(points.get(0).latitude)));
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (i > 0) longitude += longitudeDelta(point.longitude - points.get(i - 1).longitude);
            positions[i][0] = longitude * scale;
            positions[i][1] = -point.latitude;
        }
        return positions;
    }

    static boolean fresh(long now, long fixTime) {
        return fixTime > 0 && now >= fixTime && now - fixTime <= FRESH_MS;
    }

    static final class ArrivalGate {
        private long firstTime = -1, lastTime = -1;
        private int fixes;

        void reset() { firstTime = lastTime = -1; fixes = 0; }

        boolean update(boolean driving, boolean precise, boolean gps, long now, long fixTime,
                       double accuracy, double distance) {
            double radius = driving ? 30 : 15, maxAccuracy = driving ? 20 : 12;
            if (!precise || !gps || !fresh(now, fixTime) || !Double.isFinite(accuracy)
                    || accuracy < 0 || accuracy > maxAccuracy || !Double.isFinite(distance)
                    || distance < 0 || distance + accuracy > radius) {
                reset();
                return false;
            }
            if (fixTime <= lastTime) return false;
            if (lastTime >= 0 && fixTime - lastTime > FRESH_MS) reset();
            if (firstTime < 0) firstTime = fixTime;
            lastTime = fixTime;
            fixes++;
            return fixes >= 3 && fixTime - firstTime >= (driving ? 2000 : 3000);
        }
    }
}
