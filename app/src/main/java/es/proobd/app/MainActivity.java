package es.proobd.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements ObdLink.Listener {

    private WebView web;
    private volatile boolean pageReady;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ObdLink link;
    private String lastStatus = "off", lastStatusMsg = "Sin conectar";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        prefs = getSharedPreferences("proobd", Context.MODE_PRIVATE);

        link = new ObdLink(this);
        applyConfig();
        link.setTrip(prefs.getFloat("tripKm", 0), prefs.getFloat("tripL", 0), prefs.getLong("tripLast", 0));

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0F14);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setTextZoom(100);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                js("OBD.onStatus(" + JSONObject.quote(lastStatus) + "," + JSONObject.quote(lastStatusMsg) + ")");
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        askPermissions();
        handler.postDelayed(() -> checkUpdate(false), 5000);
    }

    @Override
    protected void onDestroy() {
        saveTrip();
        link.stop();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        saveTrip();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Se queda conectada en segundo plano (por si cambias a Pro Maps o Pro Radio).
        moveTaskToBack(true);
    }

    private void saveTrip() {
        if (link == null || prefs == null) return;
        double[] t = link.trip();
        prefs.edit().putFloat("tripKm", (float) t[0]).putFloat("tripL", (float) t[1]).putLong("tripLast", (long) t[2]).apply();
    }

    private void applyConfig() {
        double disp = "1.3".equals(prefs.getString("engine", "1.2")) ? 1.332 : 1.197;
        link.setConfig(disp, prefs.getFloat("correction", 1f));
    }

    // ---------------------------------------------------------------- permisos y Bluetooth

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> miss = new ArrayList<>();
            for (String p : new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
            }
            if (!miss.isEmpty()) {
                requestPermissions(miss.toArray(new String[0]), 1);
                return;
            }
        }
        autoConnect();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!hasBtPermission()) {
            onStatus("error", "Sin permiso de Bluetooth. Pulsa «Conectar» para darlo.");
            return;
        }
        js("OBD.refreshDevices && OBD.refreshDevices()");
        autoConnect();
    }

    private void autoConnect() {
        String addr = prefs.getString("device", "");
        if (!addr.isEmpty() && !link.isRunning()) link.start(addr);
    }

    @SuppressLint("MissingPermission")
    private String pairedJson() {
        JSONArray arr = new JSONArray();
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !hasBtPermission()) return arr.toString();
            Set<BluetoothDevice> set = ad.getBondedDevices();
            if (set == null) return arr.toString();
            for (BluetoothDevice d : set) {
                String n = d.getName() == null ? "(sin nombre)" : d.getName();
                String up = n.toUpperCase();
                boolean obd = up.contains("OBD") || up.contains("ELM") || up.contains("VLINK") || up.contains("V-LINK")
                        || up.contains("ICAR") || up.contains("VGATE") || up.contains("KONNWEI") || up.contains("VEEPEAK")
                        || up.contains("SCAN") || up.contains("CHX") || up.contains("CAR");
                arr.put(new JSONObject().put("name", n).put("address", d.getAddress()).put("obd", obd));
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    // ---------------------------------------------------------------- avisos de ObdLink (hilo secundario)

    @Override
    public void onStatus(String state, String message) {
        lastStatus = state;
        lastStatusMsg = message;
        js("OBD.onStatus(" + JSONObject.quote(state) + "," + JSONObject.quote(message) + ")");
    }

    @Override
    public void onData(JSONObject data) {
        js("OBD.onData(" + data + ")");
    }

    @Override
    public void onDtc(JSONObject result) {
        js("OBD.onDtc(" + result + ")");
    }

    @Override
    public void onLog(String line) {
        js("OBD.onLog(" + JSONObject.quote(line) + ")");
    }

    private void js(String code) {
        if (!pageReady) return;
        runOnUiThread(() -> web.evaluateJavascript("window.OBD && " + code, null));
    }

    private void toast(String msg) {
        js("OBD.toast(" + JSONObject.quote(msg) + ")");
    }

    // ---------------------------------------------------------------- actualizaciones (igual que Pro Maps y Pro Radio)

    private void checkUpdate(boolean manual) {
        if (manual) toast("Buscando actualizaciones…");
        new Thread(() -> {
            Updater.Info info = Updater.latest();
            int mine = Updater.installedCode(this);
            handler.post(() -> {
                if (isFinishing()) return;
                if (info == null) {
                    if (manual) toast("No se pudo comprobar. ¿Tienes internet?");
                    return;
                }
                if (info.code <= mine) {
                    if (manual) toast("Ya tienes la última versión (v" + Updater.installedName(this) + ")");
                    return;
                }
                if (!manual && prefs.getInt("skipVersion", 0) == info.code) return;
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Hay una versión nueva")
                        .setMessage("Pro OBD v" + info.name + " ya está disponible (tienes la v" + Updater.installedName(this) + ")."
                                + (info.notes.isEmpty() ? "" : "\n\nNovedades:\n" + info.notes)
                                + "\n\nTus ajustes se mantienen.")
                        .setPositiveButton("Actualizar", (d, w) -> startUpdate())
                        .setNeutralButton("Esta no", (d, w) -> prefs.edit().putInt("skipVersion", info.code).apply())
                        .setNegativeButton("Más tarde", null)
                        .show();
            });
        }).start();
    }

    private void startUpdate() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Falta un permiso")
                    .setMessage("Para actualizarse, Android tiene que permitir que Pro OBD instale apps.\n\n"
                            + "En la siguiente pantalla activa «Permitir de esta fuente», vuelve atrás y pulsa otra vez Actualizar.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        toast("Descargando la versión nueva…");
        new Thread(() -> {
            String err = Updater.downloadAndInstall(this);
            if (err != null) handler.post(() -> toast(err));
        }).start();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !Updater.ACTION_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            toast("No se pudo instalar" + (msg == null ? "" : ": " + msg));
        }
    }

    // ---------------------------------------------------------------- puente con la pantalla (JavaScript)

    final class Bridge {
        @JavascriptInterface
        public String paired() { return pairedJson(); }

        @JavascriptInterface
        public String device() { return prefs.getString("device", ""); }

        @JavascriptInterface
        public void connect(String address) {
            runOnUiThread(() -> {
                if (!hasBtPermission()) { askPermissions(); return; }
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad != null && !ad.isEnabled()) {
                    try {
                        startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                    } catch (Exception ignored) {
                    }
                }
                prefs.edit().putString("device", address).apply();
                link.start(address);
            });
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(() -> {
                prefs.edit().putString("device", "").apply();
                link.stop();
            });
        }

        @JavascriptInterface
        public void readDtc() { link.request("read"); }

        @JavascriptInterface
        public void clearDtc() { link.request("clear"); }

        @JavascriptInterface
        public void resetTrip() { link.resetTrip(); saveTrip(); }

        @JavascriptInterface
        public void setLog(boolean on) { link.setLog(on); }

        @JavascriptInterface
        public void setPref(String key, String value) {
            if ("correction".equals(key)) {
                try {
                    prefs.edit().putFloat("correction", Float.parseFloat(value)).apply();
                } catch (Exception ignored) {
                }
            } else {
                prefs.edit().putString(key, value).apply();
            }
            applyConfig();
        }

        @JavascriptInterface
        public String getPref(String key, String def) {
            if ("correction".equals(key)) return String.valueOf(prefs.getFloat("correction", 1f));
            return prefs.getString(key, def);
        }

        @JavascriptInterface
        public void openBtSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (Exception e) {
                    toast("No se pudieron abrir los ajustes de Bluetooth");
                }
            });
        }

        @JavascriptInterface
        public String version() { return Updater.installedName(MainActivity.this); }

        @JavascriptInterface
        public void checkUpdate() { runOnUiThread(() -> MainActivity.this.checkUpdate(true)); }
    }
}
