package com.xstreaming;

import android.os.SystemClock;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;

@ReactModule(name = GamepadManager.NAME)
public class GamepadManager extends ReactContextBaseJavaModule {
    public static final String NAME = "GamepadManager";
    private final ReactApplicationContext reactContext;
    private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;

    // Controlo de Throttling e Filtro de Eixos para poupar a CPU Cortex-A55 e o GC
    private static final float AXIS_DEADZONE = 0.05f;
    private static final float AXIS_DELTA_THRESHOLD = 0.012f;
    private static final long MIN_EVENT_INTERVAL_MS = 8; // Máximo ~125Hz

    private float lastLX = 0f, lastLY = 0f;
    private float lastRX = 0f, lastRY = 0f;
    private float lastLT = 0f, lastRT = 0f;
    private int lastDpadX = 0, lastDpadY = 0;
    private long lastAxisEventTime = 0;

    public GamepadManager(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return NAME;
    }

    private DeviceEventManagerModule.RCTDeviceEventEmitter getEmitter() {
        if (eventEmitter == null && reactContext.hasActiveCatalystInstance()) {
            eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
        }
        return eventEmitter;
    }

    private float applyDeadzone(float value) {
        return Math.abs(value) < AXIS_DEADZONE ? 0f : value;
    }

    /**
     * Envia eventos de botões (KeyDown / KeyUp) imediatamente sem atraso (Zero-Latency)
     */
    public void sendKeyEvent(String eventName, int keyCode, int deviceId) {
        DeviceEventManagerModule.RCTDeviceEventEmitter emitter = getEmitter();
        if (emitter == null) return;

        WritableMap params = Arguments.createMap();
        params.putInt("keyCode", keyCode);
        params.putInt("deviceId", deviceId);
        emitter.emit(eventName, params);
    }

    /**
     * Envia eventos de D-Pad (Setas direcionais do comando ou controlo remoto da TV)
     */
    public void sendDpadEvent(float hatX, float hatY, int deviceId) {
        int dpadX = Math.round(hatX);
        int dpadY = Math.round(hatY);

        // Só emite se o estado das setas realmente mudou
        if (dpadX == lastDpadX && dpadY == lastDpadY) {
            return;
        }
        lastDpadX = dpadX;
        lastDpadY = dpadY;

        DeviceEventManagerModule.RCTDeviceEventEmitter emitter = getEmitter();
        if (emitter == null) return;

        WritableMap params = Arguments.createMap();
        params.putInt("dpadX", dpadX);
        params.putInt("dpadY", dpadY);
        params.putInt("deviceId", deviceId);
        emitter.emit("onDpadEvent", params);
    }

    /**
     * Filtra e agrupa eventos de Analógicos e Gatilhos para evitar GC Churn na TV
     */
    public void sendMotionEvent(float lx, float ly, float rx, float ry, float lt, float rt, int deviceId) {
        lx = applyDeadzone(lx);
        ly = applyDeadzone(ly);
        rx = applyDeadzone(rx);
        ry = applyDeadzone(ry);
        lt = applyDeadzone(lt);
        rt = applyDeadzone(rt);

        long now = SystemClock.uptimeMillis();
        boolean returnedToZero = (lx == 0f && ly == 0f && rx == 0f && ry == 0f && lt == 0f && rt == 0f) &&
                                 (lastLX != 0f || lastLY != 0f || lastRX != 0f || lastRY != 0f || lastLT != 0f || lastRT != 0f);

        // Se não voltou a zero (repouso), respeita o intervalo de 8ms (125Hz)
        if (!returnedToZero && (now - lastAxisEventTime < MIN_EVENT_INTERVAL_MS)) {
            return;
        }

        // Verifica se a variação supera o limiar mínimo
        if (!returnedToZero &&
            Math.abs(lx - lastLX) < AXIS_DELTA_THRESHOLD &&
            Math.abs(ly - lastLY) < AXIS_DELTA_THRESHOLD &&
            Math.abs(rx - lastRX) < AXIS_DELTA_THRESHOLD &&
            Math.abs(ry - lastRY) < AXIS_DELTA_THRESHOLD &&
            Math.abs(lt - lastLT) < AXIS_DELTA_THRESHOLD &&
            Math.abs(rt - lastRT) < AXIS_DELTA_THRESHOLD) {
            return;
        }

        lastLX = lx; lastLY = ly;
        lastRX = rx; lastRY = ry;
        lastLT = lt; lastRT = rt;
        lastAxisEventTime = now;

        DeviceEventManagerModule.RCTDeviceEventEmitter emitter = getEmitter();
        if (emitter == null) return;

        WritableMap params = Arguments.createMap();
        params.putDouble("leftStickX", lx);
        params.putDouble("leftStickY", ly);
        params.putDouble("rightStickX", rx);
        params.putDouble("rightStickY", ry);
        params.putDouble("leftTrigger", lt);
        params.putDouble("rightTrigger", rt);
        params.putInt("deviceId", deviceId);
        emitter.emit("onAxisEvent", params);
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Requerido pelo NativeEventEmitter do React Native
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Requerido pelo NativeEventEmitter do React Native
    }
}
