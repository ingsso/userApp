package com.example.usertestmap;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.*;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.location.*;
import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;
import com.google.firebase.database.*;
import com.firebase.geofire.core.GeoHash;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends FragmentActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private ImageView alertIcon;
    private TextView distanceText;
    private Vibrator vibrator;
    private boolean isVibrating = false;

    private final List<CCTVLocation> cachedCCTVList = new ArrayList<>();
    private final Set<String> notifiedCCTVSet = new HashSet<>();
    private final ExecutorService calculationExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isCalculating = false;
    private long lastCalculationTime = 0;
    private static final long MIN_CALCULATION_INTERVAL = 100;

    private double currentLat, currentLng;
    private static final double EARTH_RADIUS_M = 6371000.0;
    private static final double DEG_TO_RAD = Math.PI / 180.0;

    private DatabaseReference currentCCTVRef;
    private ValueEventListener cctvListener;
    private String currentGeoHash = "";

    private static class CCTVLocation {
        final double lat, lng;
        final String key;
        CCTVLocation(double lat, double lng) {
            this.lat = lat;
            this.lng = lng;
            this.key = lat + "," + lng;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        alertIcon = findViewById(R.id.alert_icon);
        distanceText = findViewById(R.id.distance_text);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation();
            startLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        mMap.setMyLocationEnabled(true);
    }

    private void startLocationUpdates() {
        LocationRequest locationRequest = LocationRequest.create()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setInterval(500)
                .setFastestInterval(300)
                .setSmallestDisplacement(0.1f);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location == null) return;

                currentLat = location.getLatitude();
                currentLng = location.getLongitude();

                Log.d("현재위치", "Lat: " + currentLat + ", Lng: " + currentLng);

                LatLng myLatLng = new LatLng(currentLat, currentLng);
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myLatLng, 21));

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastCalculationTime > MIN_CALCULATION_INTERVAL && !isCalculating) {
                    lastCalculationTime = currentTime;
                    checkDistanceToCachedCCTVsAsync();
                }

                String newGeoHash = new GeoHash(currentLat, currentLng, 6).getGeoHashString();
                if (!newGeoHash.equals(currentGeoHash)) {
                    currentGeoHash = newGeoHash;
                    subscribeToCCTVs(currentGeoHash);
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    private void subscribeToCCTVs(String geoHash) {
        if (currentCCTVRef != null && cctvListener != null) {
            currentCCTVRef.removeEventListener(cctvListener);
        }

        currentCCTVRef = FirebaseDatabase.getInstance().getReference("obstacles").child(geoHash);
        cctvListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                calculationExecutor.execute(() -> {
                    List<CCTVLocation> newCCTVList = new ArrayList<>();
                    List<MarkerOptions> markersToAdd = new ArrayList<>();

                    for (DataSnapshot cctvSnapshot : snapshot.getChildren()) {
                        try {
                            double lat = cctvSnapshot.child("latitude").getValue(Double.class);
                            double lng = cctvSnapshot.child("longitude").getValue(Double.class);

                            float distance = haversineDistance(currentLat, currentLng, lat, lng);
                            if (distance <= 300) {
                                newCCTVList.add(new CCTVLocation(lat, lng));
                                markersToAdd.add(new MarkerOptions().position(new LatLng(lat, lng))
                                        .title("CCTV")
                                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    mainHandler.post(() -> {
                        synchronized (cachedCCTVList) {
                            cachedCCTVList.clear();
                            cachedCCTVList.addAll(newCCTVList);
                        }

                        mMap.clear();
                        for (MarkerOptions marker : markersToAdd) {
                            mMap.addMarker(marker);
                        }

                        if (!newCCTVList.isEmpty()) {
                            checkDistanceToCachedCCTVsAsync();
                        }
                    });
                });
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        currentCCTVRef.addValueEventListener(cctvListener);
    }

    private float haversineDistance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = (lat2 - lat1) * DEG_TO_RAD;
        double dLng = (lng2 - lng1) * DEG_TO_RAD;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1 * DEG_TO_RAD) * Math.cos(lat2 * DEG_TO_RAD)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (float) (EARTH_RADIUS_M * c);
    }

    private void checkDistanceToCachedCCTVsAsync() {
        if (isCalculating) return;
        isCalculating = true;

        calculationExecutor.execute(() -> {
            try {
                boolean inRange = false;
                float closestDistance = Float.MAX_VALUE;

                List<CCTVLocation> cctvListCopy;
                synchronized (cachedCCTVList) {
                    cctvListCopy = new ArrayList<>(cachedCCTVList);
                }

                for (CCTVLocation cctvLoc : cctvListCopy) {
                    float distance = haversineDistance(currentLat, currentLng, cctvLoc.lat, cctvLoc.lng);
                    Log.d("거리계산", "현재: " + currentLat + ", " + currentLng +
                            " | CCTV: " + cctvLoc.lat + ", " + cctvLoc.lng + " | 거리: " + distance);

                    if (distance < closestDistance) {
                        closestDistance = distance;
                    }

                    if (distance <= 4f) {
                        inRange = true;
                        if (!notifiedCCTVSet.contains(cctvLoc.key)) {
                            notifiedCCTVSet.add(cctvLoc.key);
                            long[] pattern = distance <= 2
                                    ? new long[]{0, 50, 50, 50, 50, 50, 50, 50}
                                    : new long[]{0, 300, 300, 300};
                            mainHandler.post(() -> {
                                if (!isVibrating && vibrator != null) {
                                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                                    isVibrating = true;
                                }
                                alertIcon.setVisibility(View.VISIBLE);
                            });
                        }
                    }
                }

                final float finalClosestDistance = closestDistance;
                final boolean finalInRange = inRange;
                mainHandler.post(() -> updateUI(finalClosestDistance, finalInRange));

            } finally {
                isCalculating = false;
            }
        });
    }

    @SuppressLint("DefaultLocale")
    private void updateUI(float closestDistance, boolean inRange) {
        distanceText.setText(closestDistance != Float.MAX_VALUE
                ? String.format("거리: %.1f m", closestDistance)
                : "거리: -- m");

        if (!inRange) {
            stopVibrationIfNeeded();
            notifiedCCTVSet.clear();
        }
    }

    private void stopVibrationIfNeeded() {
        if (vibrator != null && isVibrating) {
            vibrator.cancel();
            isVibrating = false;
            alertIcon.setVisibility(View.GONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation();
            startLocationUpdates();
        }
    }

    @Override protected void onPause() {
        super.onPause(); stopVibrationIfNeeded();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    @Override protected void onStop() {
        super.onStop(); stopVibrationIfNeeded();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (currentCCTVRef != null && cctvListener != null) {
            currentCCTVRef.removeEventListener(cctvListener);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (currentCCTVRef != null && cctvListener != null) {
            currentCCTVRef.removeEventListener(cctvListener);
        }
        calculationExecutor.shutdown();
    }

    @Override protected void onResume() {
        super.onResume();
        startLocationUpdates();
    }
}
