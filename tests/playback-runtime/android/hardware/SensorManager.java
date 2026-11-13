package android.hardware;
public class SensorManager {
    public Sensor getDefaultSensor(int type) { return null; }
    public boolean registerListener(SensorEventListener listener,Sensor sensor,int period) { return false; }
    public void unregisterListener(SensorEventListener listener) {}
}
