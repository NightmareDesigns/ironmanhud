package com.nightmaredesigns.dexhud;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.util.Locale;

/** One process-local owner. A playback mediaProjection service never authorizes background GPS. */
final class NavigationController implements SensorEventListener, LocationListener {
    private final Context context;
    private final HudState state;
    private final SensorManager sensors;
    private final LocationManager locations;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OfflineRoute.ArrivalGate arrival = new OfflineRoute.ArrivalGate();
    private final float[] rotationMatrix = new float[9];
    private OfflineRoute route;
    private Location fix;
    private int target, sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private boolean driving, active, visible, sensing, locating, finalReached;
    private long sensorTime, lastFixTime, lastPaint;
    private String locationStatus = "No route • compass only";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            reconcileLocation();
            if (fix != null && !OfflineRoute.fresh(SystemClock.elapsedRealtime(), fixTime(fix))) {
                clearFix();
                locationStatus = "GPS stale • guidance paused";
            }
            state.changed();
            if (active) main.postDelayed(this, 1000);
        }
    };

    NavigationController(Context context, HudState state) {
        this.context = context.getApplicationContext();
        this.state = state;
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        locations = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    boolean permitted() {
        return precise() || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean precise() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    OfflineRoute route() { return route; }
    boolean driving() { return driving; }

    void start(OfflineRoute supplied, boolean drive) {
        if (!active || !visible || !permitted() || !state.enabled("offlineNavigation", false)) return;
        route = supplied;
        driving = drive;
        target = 0;
        clearFix();
        lastFixTime = SystemClock.elapsedRealtime();
        locationStatus = "Waiting for fresh GPS";
        reconcileLocation();
        state.changed();
    }

    void stop() {
        route = null;
        target = 0;
        stopLocation();
        locationStatus = "No route • compass only";
        state.changed();
    }

    void step(int offset) {
        if (!active || route == null) return;
        target = Math.max(0, Math.min(route.points.size() - 1, target + offset));
        arrival.reset();
        finalReached = false;
        state.changed();
    }

    void update(boolean hudActive, boolean activityVisible) {
        boolean enabled = state.enabled("offlineNavigation", false);
        visible = activityVisible;
        boolean needed = enabled && hudActive;
        if (!enabled) {
            route = null;
            target = 0;
        }
        if (needed != active) {
            active = needed;
            if (active) {
                Sensor sensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
                sensorTime = 0;
                sensing = sensor != null && sensors.registerListener(this, sensor,
                        SensorManager.SENSOR_DELAY_UI, main);
                main.postDelayed(tick, 1000);
            } else {
                if (sensors != null) sensors.unregisterListener(this);
                sensing = false;
                sensorTime = 0;
                sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
                main.removeCallbacks(tick);
            }
        }
        reconcileLocation();
    }

    private void reconcileLocation() {
        if (!active || route == null) {
            stopLocation();
            locationStatus = "No route • compass only";
            return;
        }
        if (!visible || !permitted() || !precise()) {
            stopLocation();
            locationStatus = !visible ? "GPS paused • app not visible"
                    : !permitted() ? "Location permission denied/revoked • guidance paused"
                    : "Approximate permission only • enable precise location for GPS guidance";
            return;
        }
        if (locations == null) {
            stopLocation();
            locationStatus = "GPS unavailable • guidance paused";
            return;
        }
        try {
            if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                stopLocation();
                locationStatus = "GPS disabled • guidance paused";
            } else if (!locating) {
                clearFix();
                lastFixTime = SystemClock.elapsedRealtime();
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
                locating = true;
                locationStatus = "Waiting for fresh GPS";
            }
        } catch (SecurityException exception) {
            stopLocation();
            locationStatus = "Location access blocked • guidance paused";
        } catch (IllegalArgumentException exception) {
            stopLocation();
            locationStatus = "GPS unavailable • guidance paused";
        }
    }

    private void clearFix() {
        fix = null;
        finalReached = false;
        arrival.reset();
    }

    private void stopLocation() {
        if (locating && locations != null) {
            try { locations.removeUpdates(this); } catch (SecurityException ignored) { }
        }
        locating = false;
        clearFix();
    }

    private static long fixTime(Location location) { return location.getElapsedRealtimeNanos() / 1000000; }

    private boolean usable(Location location) {
        return location != null && precise() && LocationManager.GPS_PROVIDER.equals(location.getProvider())
                && OfflineRoute.fresh(SystemClock.elapsedRealtime(), fixTime(location))
                && location.hasAccuracy() && Float.isFinite(location.getAccuracy())
                && location.getAccuracy() >= 0 && location.getAccuracy() <= (driving ? 100 : 50)
                && Double.isFinite(location.getLatitude()) && Math.abs(location.getLatitude()) <= 90
                && Double.isFinite(location.getLongitude()) && Math.abs(location.getLongitude()) <= 180;
    }

    @Override public void onLocationChanged(Location location) {
        if (!active || !visible || route == null || !locating) return;
        if (!permitted() || !precise()) { reconcileLocation(); state.changed(); return; }
        long time = fixTime(location);
        if (time <= lastFixTime) return;
        if (OfflineRoute.fresh(SystemClock.elapsedRealtime(), time)) lastFixTime = time;
        if (!usable(location)) {
            clearFix();
            locationStatus = "GPS stale/inaccurate • guidance paused";
            state.changed();
            return;
        }
        fix = new Location(location);
        locationStatus = location.isFromMockProvider() ? "Mock GPS • manual guidance only" : "GPS estimate";
        double distance = OfflineRoute.distance(fix.getLatitude(), fix.getLongitude(), route.points.get(target));
        if (finalReached && (distance + fix.getAccuracy() > (driving ? 30 : 15)
                || fix.getAccuracy() > (driving ? 20 : 12) || fix.isFromMockProvider())) finalReached = false;
        if (!finalReached && arrival.update(driving, precise(), LocationManager.GPS_PROVIDER.equals(location.getProvider())
                        && !location.isFromMockProvider(), SystemClock.elapsedRealtime(), time, location.getAccuracy(), distance)) {
            if (target < route.points.size() - 1) target++;
            else finalReached = true;
            arrival.reset(); // At most one waypoint per distinct fix; dwell again at the next point.
        }
        state.changed();
    }

    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            stopLocation();
            locationStatus = "GPS disabled • guidance paused";
            state.changed();
        }
    }
    @Override public void onProviderEnabled(String provider) { reconcileLocation(); state.changed(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!active || !sensing || event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        sensorAccuracy = event.accuracy;
        if (event.values.length < 3) { sensorTime = 0; return; }
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        sensorTime = event.timestamp / 1000000;
        long now = SystemClock.elapsedRealtime();
        if (now - lastPaint >= 250 || sensorAccuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            lastPaint = now;
            state.changed();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        sensorAccuracy = accuracy;
        if (active) state.changed();
    }

    Snapshot snapshot() {
        long now = SystemClock.elapsedRealtime();
        boolean reliable = active && sensing && sensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                && sensorTime > 0 && now >= sensorTime && now - sensorTime <= 5000;
        String compassStatus = !sensing ? "Phone compass unavailable"
                : sensorAccuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ? "Phone compass unreliable • calibrate away from magnets"
                : !reliable ? "Phone compass waiting/stale" : "";
        String guidance = "No route • compass only\nNavigation: paste your planned waypoints.";
        String routeHeader = guidance;
        long guidanceExpires = 0;
        if (route != null) {
            OfflineRoute.Point point = route.points.get(target);
            String mode = driving ? "Driving tolerances" : "Walking tolerances";
            guidance = mode + " • " + (route.points.size() == 1 ? "direct bearing / straight line" : "supplied path, not street routing")
                    + "\n" + (target + 1) + "/" + route.points.size() + " • " + point.label
                    + "\n" + (route.points.size() - target - 1) + " waypoints after current";
            routeHeader = guidance;
            if (active && visible && locating && usable(fix)) {
                guidanceExpires = fixTime(fix) + OfflineRoute.FRESH_MS;
                double distance = OfflineRoute.distance(fix.getLatitude(), fix.getLongitude(), point);
                guidance += String.format(Locale.US, "\n%.0f m • target bearing %.0f° true\nGPS accuracy ±%.0f m",
                        distance, OfflineRoute.bearing(fix.getLatitude(), fix.getLongitude(), point), fix.getAccuracy());
                if (fix.isFromMockProvider()) guidance += "\nMock GPS • manual guidance only";
                if (finalReached) guidance += "\nFinal waypoint reached (GPS estimate)";
                if (fix.hasBearing() && fix.hasSpeed() && fix.getSpeed() >= (driving ? 3 : 1.5)
                        && Float.isFinite(fix.getSpeed())
                        && fix.hasBearingAccuracy() && Float.isFinite(fix.getBearing())
                        && Float.isFinite(fix.getBearingAccuracyDegrees()) && fix.getBearingAccuracyDegrees() >= 0
                        && fix.getBearingAccuracyDegrees() <= 30) {
                    guidance += String.format(Locale.US, "\nGPS travel course %.0f° true (not compass)", OfflineRoute.normalize(fix.getBearing()));
                }
            } else {
                String status = !visible ? "GPS paused • app not visible"
                        : !permitted() ? "Location permission denied/revoked • guidance paused"
                        : !precise() ? "Approximate permission only • enable precise location for GPS guidance"
                        : fix != null && !OfflineRoute.fresh(now, fixTime(fix)) ? "GPS stale • guidance paused"
                        : locationStatus;
                guidance += "\n" + status;
            }
        }
        return new Snapshot(route, target, guidance, reliable ? rotationMatrix.clone() : null, compassStatus,
                reliable ? sensorTime + 5000 : 0, guidanceExpires, routeHeader, context);
    }

    static final class Snapshot {
        final OfflineRoute route;
        final int target;
        final String guidance, compassStatus;
        final float[] matrix;
        private final long compassExpires, guidanceExpires;
        private final String routeHeader;
        private final Context context;
        Snapshot(OfflineRoute route, int target, String guidance, float[] matrix, String compassStatus,
                 long compassExpires, long guidanceExpires, String routeHeader, Context context) {
            this.route = route;
            this.target = target;
            this.guidance = guidance;
            this.matrix = matrix;
            this.compassStatus = compassStatus;
            this.compassExpires = compassExpires;
            this.guidanceExpires = guidanceExpires;
            this.routeHeader = routeHeader;
            this.context = context;
        }

        String guidanceText() {
            if (guidanceExpires > 0) {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    return routeHeader + "\nLocation permission changed • guidance paused";
                }
                if (SystemClock.elapsedRealtime() > guidanceExpires) return routeHeader + "\nGPS stale • guidance paused";
            }
            return guidance;
        }

        String phoneHeading(int rotation) {
            if (matrix == null) return compassStatus + "\nMagnetic north • phone, NOT glasses pose";
            if (SystemClock.elapsedRealtime() > compassExpires) return "Phone compass stale\nMagnetic north • phone, NOT glasses pose";
            int x = SensorManager.AXIS_X, y = SensorManager.AXIS_Y;
            switch (rotation) {
                case Surface.ROTATION_90: x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X; break;
                case Surface.ROTATION_180: x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y; break;
                case Surface.ROTATION_270: x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X; break;
                default: break;
            }
            float[] remapped = new float[9], orientation = new float[3];
            if (!SensorManager.remapCoordinateSystem(matrix, x, y, remapped)) return "Phone compass unavailable";
            SensorManager.getOrientation(remapped, orientation);
            // A near-vertical screen has no stable horizontal screen-up bearing.
            double horizontal = Math.hypot(remapped[1], remapped[4]);
            double degrees = OfflineRoute.normalize(Math.toDegrees(orientation[0]));
            if (!Double.isFinite(degrees) || !Double.isFinite(horizontal) || horizontal < .15) {
                return "Phone compass uncertain • hold phone flatter\nNot glasses pose";
            }
            return String.format(Locale.US, "%s • %.0f°\nPhone screen-up • magnetic north\nNOT glasses pose", OfflineRoute.cardinal(degrees), degrees);
        }
    }
}
