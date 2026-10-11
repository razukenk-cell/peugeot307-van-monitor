package com.razukenk.peugeot307vanmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * v0.12 passive listener for the vendor car_server Binder.
 *
 * Safety contract:
 * - calls only getService(), registerCallback(), setCallbackFilter(), unregisterCallback();
 * - NEVER calls IService.request() or requestOneway();
 * - callback Binder only accepts incoming transactions and logs them;
 * - no /dev/ttyCanbus open/read/write;
 * - no CarData setters.
 */
final class CarServerSniffer {
    private static final String SERVICE_NAME = "car_server";
    private static final String SERVICE_DESCRIPTOR = "android.cartech.aidl.common.IService";
    private static final String CALLBACK_DESCRIPTOR = "android.cartech.aidl.common.ICallback";

    private final Context context;
    private final SharedPreferences prefs;

    private Object service;
    private Object callbackProxy;
    private RawCallbackBinder callbackBinder;
    private Method unregisterCallback;
    private boolean registered;

    CarServerSniffer(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
    }

    synchronized String start() {
        if (registered) {
            return prefs.getString("car_server_status", "уже зарегистрирован");
        }

        StringBuilder diag = new StringBuilder();
        diag.append("# Peugeot 307 CarInfo v0.12 car_server passive callback sniffer\n");
        diag.append("# ALLOWED: registerCallback, setCallbackFilter, unregisterCallback only\n");
        diag.append("# FORBIDDEN/NOT CALLED: request, requestOneway, UART open/read/write, CarData setters\n");
        diag.append("time=").append(new Date()).append('\n');
        diag.append("uid=").append(Process.myUid()).append(" pid=").append(Process.myPid()).append('\n');

        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object raw = getService.invoke(null, SERVICE_NAME);
            if (!(raw instanceof IBinder)) {
                throw new IllegalStateException("ServiceManager.getService(car_server) returned " + raw);
            }
            IBinder serviceBinder = (IBinder) raw;
            diag.append("serviceBinder descriptor=").append(safeDescriptor(serviceBinder))
                    .append(" alive=").append(serviceBinder.isBinderAlive()).append('\n');

            Class<?> serviceInterface = Class.forName(SERVICE_DESCRIPTOR);
            Class<?> serviceStub = Class.forName(SERVICE_DESCRIPTOR + "$Stub");
            Method asInterface = serviceStub.getMethod("asInterface", IBinder.class);
            service = asInterface.invoke(null, serviceBinder);
            if (service == null) throw new IllegalStateException("IService.Stub.asInterface returned null");

            Class<?> callbackInterface = Class.forName(CALLBACK_DESCRIPTOR);
            Class<?> callbackStub = Class.forName(CALLBACK_DESCRIPTOR + "$Stub");

            int syncCode = transactionCode(callbackStub, "TRANSACTION_onCallback", IBinder.FIRST_CALL_TRANSACTION);
            int onewayCode = transactionCode(callbackStub, "TRANSACTION_onCallbackOneway",
                    IBinder.FIRST_CALL_TRANSACTION + 1);

            diag.append("callback transaction onCallback=").append(syncCode)
                    .append(" onCallbackOneway=").append(onewayCode).append('\n');
            reflectApi(serviceInterface, "IService", diag);
            reflectApi(callbackInterface, "ICallback", diag);
            reflectTransactionFields(serviceStub, "IService.Stub", diag);
            reflectTransactionFields(callbackStub, "ICallback.Stub", diag);

            callbackBinder = new RawCallbackBinder(this, syncCode, onewayCode);
            ClassLoader loader = callbackInterface.getClassLoader();
            if (loader == null) loader = context.getClassLoader();
            callbackProxy = Proxy.newProxyInstance(
                    loader,
                    new Class<?>[]{callbackInterface},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("asBinder".equals(name)) return callbackBinder;
                        if ("onCallback".equals(name) || "onCallbackOneway".equals(name)) {
                            String action = args != null && args.length > 0 ? String.valueOf(args[0]) : null;
                            Bundle bundle = args != null && args.length > 1 && args[1] instanceof Bundle
                                    ? (Bundle) args[1] : null;
                            onIncoming(name, action, bundle, -1, 0);
                            return method.getReturnType() == int.class ? 0 : null;
                        }
                        if ("toString".equals(name)) return "Peugeot307CarInfoRawICallback";
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                        return defaultValue(method.getReturnType());
                    });

            Method register = serviceInterface.getMethod("registerCallback", String.class, callbackInterface);
            Method setFilter = serviceInterface.getMethod("setCallbackFilter", callbackInterface, String[].class);
            unregisterCallback = serviceInterface.getMethod("unregisterCallback", callbackInterface);

            String client = "Peugeot307CarInfo-" + Process.myPid();
            Object regResult = register.invoke(service, client, callbackProxy);
            diag.append("registerCallback client=").append(client)
                    .append(" result=").append(String.valueOf(regResult)).append('\n');

            Object filterResult = setFilter.invoke(service, callbackProxy, (Object) new String[]{"*"});
            diag.append("setCallbackFilter [*] result=").append(String.valueOf(filterResult)).append('\n');

            registered = true;
            String status = "ACTIVE: car_server callback registered"
                    + "\nregister=" + String.valueOf(regResult)
                    + " filter[*]=" + String.valueOf(filterResult)
                    + "\nwaiting for passive callbacks";
            prefs.edit()
                    .putString("car_server_status", status)
                    .putString("car_server_descriptor", safeDescriptor(serviceBinder))
                    .putInt("car_server_callback_code", syncCode)
                    .putInt("car_server_oneway_code", onewayCode)
                    .putString("car_server_register_result", String.valueOf(regResult))
                    .putString("car_server_filter_result", String.valueOf(filterResult))
                    .putBoolean("car_server_registered", true)
                    .putLong("car_server_start_ms", System.currentTimeMillis())
                    .apply();

            write("car_server_probe.log", diag.toString(), false);
            return status;
        } catch (Throwable t) {
            Throwable root = root(t);
            diag.append("START ERROR=").append(error(root)).append('\n');
            String status = "car_server sniffer ERROR: " + error(root);
            prefs.edit()
                    .putString("car_server_status", status)
                    .putBoolean("car_server_registered", false)
                    .apply();
            write("car_server_probe.log", diag.toString(), false);
            service = null;
            callbackProxy = null;
            callbackBinder = null;
            unregisterCallback = null;
            registered = false;
            return status;
        }
    }

    synchronized void stop() {
        String result = "not registered";
        if (registered && service != null && callbackProxy != null && unregisterCallback != null) {
            try {
                Object r = unregisterCallback.invoke(service, callbackProxy);
                result = String.valueOf(r);
            } catch (Throwable t) {
                result = "ERROR " + error(root(t));
            }
        }
        write("car_server_probe.log",
                timestamp() + " unregisterCallback result=" + result + "\n", true);
        prefs.edit()
                .putBoolean("car_server_registered", false)
                .putString("car_server_stop_result", result)
                .apply();
        registered = false;
        service = null;
        callbackProxy = null;
        callbackBinder = null;
        unregisterCallback = null;
    }

    boolean isRegistered() {
        return registered;
    }

    private void onIncoming(String methodName, String action, Bundle bundle, int code, int flags) {
        long count = prefs.getLong("car_server_callback_count", 0) + 1;
        String extras = bundleToString(bundle);
        String row = timestamp()
                + " CALLBACK#" + count
                + " method=" + methodName
                + " code=" + code
                + " flags=0x" + Integer.toHexString(flags)
                + " action=" + String.valueOf(action)
                + " bundle=" + extras;

        write("car_server_callbacks.log", row + "\n", true);
        prefs.edit()
                .putLong("car_server_callback_count", count)
                .putLong("car_server_last_ms", System.currentTimeMillis())
                .putString("car_server_last_action", String.valueOf(action))
                .putString("car_server_last_bundle", extras)
                .putString("car_server_last_callback", row)
                .apply();
    }

    private static int transactionCode(Class<?> stub, String fieldName, int fallback) {
        try {
            Field f = stub.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void reflectApi(Class<?> cls, String label, StringBuilder out) {
        out.append('[').append(label).append(" METHODS]\n");
        try {
            for (Method m : cls.getMethods()) {
                out.append("  ").append(m.toGenericString());
                if ("request".equals(m.getName()) || "requestOneway".equals(m.getName())) {
                    out.append("  **NEVER INVOKED**");
                }
                out.append('\n');
            }
        } catch (Throwable t) {
            out.append("  reflection error=").append(error(root(t))).append('\n');
        }
    }

    private static void reflectTransactionFields(Class<?> cls, String label, StringBuilder out) {
        out.append('[').append(label).append(" TRANSACTIONS]\n");
        try {
            for (Field f : cls.getDeclaredFields()) {
                if (!f.getName().startsWith("TRANSACTION_")) continue;
                try {
                    f.setAccessible(true);
                    out.append("  ").append(f.getName()).append('=').append(String.valueOf(f.get(null))).append('\n');
                } catch (Throwable t) {
                    out.append("  ").append(f.getName()).append("=<blocked>\n");
                }
            }
        } catch (Throwable t) {
            out.append("  reflection error=").append(error(root(t))).append('\n');
        }
    }

    private static String bundleToString(Bundle bundle) {
        if (bundle == null) return "null";
        try {
            StringBuilder sb = new StringBuilder("{");
            Set<String> keys = bundle.keySet();
            int n = 0;
            for (String key : keys) {
                if (n++ > 0) sb.append(", ");
                Object value;
                try {
                    value = bundle.get(key);
                } catch (Throwable t) {
                    value = "<read-error:" + error(root(t)) + ">";
                }
                sb.append(key).append('[')
                        .append(value == null ? "null" : value.getClass().getName())
                        .append("]=").append(valueToString(value));
                if (n >= 96) {
                    sb.append(", …");
                    break;
                }
            }
            return sb.append('}').toString();
        } catch (Throwable t) {
            return "<bundle-error:" + error(root(t)) + ">";
        }
    }

    private static String valueToString(Object v) {
        if (v == null) return "null";
        Class<?> c = v.getClass();
        if (!c.isArray()) {
            String s = String.valueOf(v);
            return s.length() > 800 ? s.substring(0, 800) + "…" : s;
        }
        int n = Array.getLength(v);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(n, 128); i++) {
            if (i > 0) sb.append(',');
            sb.append(String.valueOf(Array.get(v, i)));
        }
        if (n > 128) sb.append(",…").append(n);
        return sb.append(']').toString();
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class) return null;
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static String safeDescriptor(IBinder binder) {
        try {
            String s = binder.getInterfaceDescriptor();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "<error:" + error(root(t)) + ">";
        }
    }

    private void write(String name, String text, boolean append) {
        try {
            File f = new File(context.getFilesDir(), name);
            try (FileWriter w = new FileWriter(f, append)) {
                w.write(text);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static Throwable root(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x;
    }

    private static String error(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m.replace('\n', ' '));
    }

    /**
     * Local Binder endpoint for remote ICallback calls.
     * We do not transact outward from this Binder.
     */
    private static final class RawCallbackBinder extends Binder {
        private final CarServerSniffer owner;
        private final int syncCode;
        private final int onewayCode;

        RawCallbackBinder(CarServerSniffer owner, int syncCode, int onewayCode) {
            this.owner = owner;
            this.syncCode = syncCode;
            this.onewayCode = onewayCode;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }

            if (code == syncCode || code == onewayCode) {
                String action = null;
                Bundle bundle = null;
                try {
                    data.enforceInterface(CALLBACK_DESCRIPTOR);
                    action = data.readString();
                    int present = data.readInt();
                    if (present != 0) {
                        bundle = Bundle.CREATOR.createFromParcel(data);
                        if (bundle != null) bundle.setClassLoader(owner.context.getClassLoader());
                    }
                    owner.onIncoming(code == syncCode ? "onCallback" : "onCallbackOneway",
                            action, bundle, code, flags);
                } catch (Throwable t) {
                    owner.write("car_server_callbacks.log",
                            timestamp() + " CALLBACK PARSE ERROR code=" + code
                                    + " flags=0x" + Integer.toHexString(flags)
                                    + " action=" + String.valueOf(action)
                                    + " error=" + error(root(t)) + "\n", true);
                }

                if (code == syncCode && reply != null) {
                    reply.writeNoException();
                    reply.writeInt(0);
                }
                return true;
            }

            return super.onTransact(code, data, reply, flags);
        }
    }
}
