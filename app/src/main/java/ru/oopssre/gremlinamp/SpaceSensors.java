package ru.oopssre.gremlinamp;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import java.util.function.Consumer;

final class SpaceSensors implements SensorEventListener {
    private final SensorManager manager;
    private final Sensor accelerometer;
    private final Consumer<SpaceTracker.Motion> receiver;
    private SpaceMotion estimator;
    private boolean active;

    SpaceSensors(Context context, Consumer<SpaceTracker.Motion> receiver) {
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.receiver = receiver;
    }

    void setActive(boolean wanted) {
        if (active == wanted) return;
        if (wanted && accelerometer != null) {
            estimator = new SpaceMotion();
            active = manager.registerListener(this, accelerometer, 40_000);
        } else if (active) {
            active = false;
            manager.unregisterListener(this);
            receiver.accept(SpaceTracker.Motion.STILL);
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (active) receiver.accept(estimator.sample(event.timestamp / 1_000_000,
                event.values[0], event.values[1], event.values[2]));
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
