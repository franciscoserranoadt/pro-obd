package es.proobd.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Conexión Bluetooth con el adaptador ELM327 (también los clónicos baratos) y lectura de datos del coche.
 * Todo funciona en un hilo propio; los resultados se mandan al Listener.
 */
final class ObdLink {

    interface Listener {
        void onStatus(String state, String message);
        void onData(JSONObject data);
        void onDtc(JSONObject result);
        void onLog(String line);
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    /** Gasolina: 14,7 g de aire por cada g de gasolina; 1 litro de gasolina pesa unos 745 g. */
    private static final double AFR = 14.7, DENSITY = 745.0;

    private final Listener listener;
    private final ConcurrentLinkedQueue<String> jobs = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private volatile boolean logOn;
    private volatile Thread thread;
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;

    private volatile String address;
    private volatile double displacement = 1.2;  // litros (1.2 DIG-T o 1.3 DIG-T)
    private volatile double correction = 1.0;    // ajuste del consumo tras comparar con la gasolinera

    private boolean can = true;
    private final boolean[] supported = new boolean[0x61];

    // Viaje
    private double tripKm, tripLitres;
    private long tripLastData;
    private long lastSample;

    ObdLink(Listener l) { this.listener = l; }

    void setConfig(double displacementLitres, double correctionFactor) {
        if (displacementLitres > 0.5 && displacementLitres < 5) displacement = displacementLitres;
        if (correctionFactor > 0.5 && correctionFactor < 1.5) correction = correctionFactor;
    }

    void setLog(boolean on) { logOn = on; }

    synchronized void setTrip(double km, double litres, long last) {
        tripKm = km; tripLitres = litres; tripLastData = last;
    }

    synchronized double[] trip() { return new double[]{tripKm, tripLitres, tripLastData}; }

    synchronized void resetTrip() { tripKm = 0; tripLitres = 0; }

    boolean isRunning() { return running; }

    /** ¿Este hilo sigue siendo la conexión actual? (al reconectar, el hilo viejo se retira solo) */
    private boolean alive() { return running && Thread.currentThread() == thread; }

    String address() { return address; }

    void start(String addr) {
        stop();
        address = addr;
        running = true;
        jobs.clear();
        thread = new Thread(this::loop, "obd");
        thread.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        closeSocket();
        if (t != null) t.interrupt();
    }

    void request(String job) { jobs.add(job); }

    // ------------------------------------------------------------------ bucle principal

    private void loop() {
        int attempt = 0;
        while (alive()) {
            try {
                listener.onStatus("connecting", attempt == 0 ? "Conectando con el adaptador…" : "Reintentando la conexión…");
                connect();
                listener.onStatus("init", "Preparando el adaptador…");
                init();
                attempt = 0;
                listener.onStatus("connected", "Conectado");
                poll();
            } catch (Exception e) {
                if (!alive()) break;
                closeSocket();
                attempt++;
                listener.onStatus("error", friendly(e));
                sleep(Math.min(15000, 3000L * attempt));
            }
        }
        if (thread == null) listener.onStatus("off", "Desconectado");
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof SecurityException) return "Falta el permiso de Bluetooth. Ábrelo en Ajustes de Android.";
        if (m.startsWith("@")) return m.substring(1);
        return "No se pudo conectar. ¿Está el adaptador enchufado y el contacto puesto?";
    }

    @SuppressLint("MissingPermission")
    private void connect() throws Exception {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) throw new Exception("@Este aparato no tiene Bluetooth.");
        if (!ad.isEnabled()) throw new Exception("@El Bluetooth está apagado. Enciéndelo.");
        String addr = address;
        if (addr == null || addr.isEmpty()) throw new Exception("@Elige tu adaptador en Ajustes.");
        BluetoothDevice dev = ad.getRemoteDevice(addr);
        try { ad.cancelDiscovery(); } catch (Exception ignored) { }

        BluetoothSocket s = null;
        Exception last = null;
        for (int way = 0; way < 3 && alive(); way++) {
            try {
                if (way == 0) s = dev.createRfcommSocketToServiceRecord(SPP);
                else if (way == 1) s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                else {
                    // Truco para algunos adaptadores clónicos y radios chinas
                    Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(dev, 1);
                }
                s.connect();
                break;
            } catch (Exception e) {
                last = e;
                try { if (s != null) s.close(); } catch (Exception ignored) { }
                s = null;
                sleep(400);
            }
        }
        if (s == null) throw last != null ? last : new IOException("sin conexión");
        if (!alive()) { try { s.close(); } catch (Exception ignored) { } throw new IOException("parado"); }
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null;
        in = null;
        out = null;
    }

    /** Manda un comando y espera al símbolo «>». */
    private String send(String cmd, long timeoutMs) throws IOException {
        InputStream i = in;
        OutputStream o = out;
        if (i == null || o == null) throw new IOException("cerrado");
        while (i.available() > 0) i.read();   // limpia restos
        o.write((cmd + "\r").getBytes("US-ASCII"));
        o.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (!alive()) throw new IOException("parado");
            int n = i.available();
            if (n > 0) {
                byte[] buf = new byte[n];
                int r = i.read(buf);
                for (int k = 0; k < r; k++) {
                    char c = (char) (buf[k] & 0xFF);
                    if (c == '>') {
                        String res = sb.toString().trim();
                        if (res.startsWith(cmd)) res = res.substring(cmd.length()).trim();  // eco
                        log(cmd, res);
                        return res;
                    }
                    if (c != 0) sb.append(c);
                }
            } else {
                sleep(8);
            }
        }
        log(cmd, sb + " (sin respuesta)");
        return sb.toString().trim();
    }

    private void log(String cmd, String res) {
        if (logOn) listener.onLog(cmd + " → " + res.replace('\r', ' ').replace('\n', ' '));
    }

    private void init() throws Exception {
        send("ATZ", 4000);
        sleep(800);
        send("ATE0", 1500);
        send("ATL0", 1000);
        send("ATS0", 1000);
        send("ATH0", 1000);
        send("ATAT1", 1000);
        send("ATSP0", 1000);
        for (int k = 0; k < supported.length; k++) supported[k] = false;

        String r = send("0100", 12000);
        if (!ObdParse.supportedPids(r, 0x00, supported)) {
            // El Qashqai J11 usa CAN 11 bits a 500 kbit/s (protocolo 6). Lo forzamos.
            send("ATSP6", 1000);
            r = send("0100", 6000);
            if (!ObdParse.supportedPids(r, 0x00, supported)) {
                throw new Exception("@El adaptador no recibe datos del coche. Pon el contacto (o arranca) y espera.");
            }
        }
        if (supported[0x20]) ObdParse.supportedPids(send("0120", 3000), 0x20, supported);
        if (supported[0x40]) ObdParse.supportedPids(send("0140", 3000), 0x40, supported);
        String dp = send("ATDPN", 1500).toUpperCase().replace("A", "");
        can = dp.isEmpty() || dp.matches(".*[6-9B-C].*");

        JSONObject info = new JSONObject();
        try {
            JSONArray pids = new JSONArray();
            for (int k = 1; k < supported.length; k++) if (supported[k]) pids.put(String.format("%02X", k));
            info.put("pids", pids);
            info.put("maf", supported[0x10]);
            info.put("can", can);
            info.put("adapter", send("ATI", 1500));
        } catch (Exception ignored) { }
        JSONObject d = new JSONObject();
        try { d.put("info", info); } catch (Exception ignored) { }
        listener.onData(d);
    }

    // ------------------------------------------------------------------ lectura continua

    private void poll() throws Exception {
        int cycle = 0;
        int empty = 0;
        double coolant = Double.NaN, volts = Double.NaN, load = Double.NaN, fuelLevel = Double.NaN,
                iat = Double.NaN, map = Double.NaN;
        lastSample = 0;
        while (alive()) {
            String job;
            while ((job = jobs.poll()) != null) runJob(job);

            double rpm = Double.NaN, speed = Double.NaN, maf = Double.NaN;
            int[] b = ObdParse.pidBytes(send("010C", 1500), 0x0C);
            if (b != null && b.length >= 2) rpm = (b[0] * 256 + b[1]) / 4.0;
            b = ObdParse.pidBytes(send("010D", 1500), 0x0D);
            if (b != null && b.length >= 1) speed = b[0];

            if (supported[0x10]) {
                b = ObdParse.pidBytes(send("0110", 1500), 0x10);
                if (b != null && b.length >= 2) maf = (b[0] * 256 + b[1]) / 100.0;
            } else if (supported[0x0B]) {
                b = ObdParse.pidBytes(send("010B", 1500), 0x0B);
                if (b != null && b.length >= 1) map = b[0];
            }

            if (cycle % 3 == 0) {
                b = ObdParse.pidBytes(send("0105", 1500), 0x05);
                if (b != null && b.length >= 1) coolant = b[0] - 40;
                b = ObdParse.pidBytes(send("0104", 1500), 0x04);
                if (b != null && b.length >= 1) load = b[0] * 100.0 / 255.0;
                if (!supported[0x10] && supported[0x0F]) {
                    b = ObdParse.pidBytes(send("010F", 1500), 0x0F);
                    if (b != null && b.length >= 1) iat = b[0] - 40;
                }
            }
            if (cycle % 5 == 0) {
                double v = ObdParse.volts(send("ATRV", 1500));
                if (!Double.isNaN(v)) volts = v;
            }
            if (cycle % 20 == 0 && supported[0x2F]) {
                b = ObdParse.pidBytes(send("012F", 1500), 0x2F);
                if (b != null && b.length >= 1) fuelLevel = b[0] * 100.0 / 255.0;
            }
            cycle++;

            boolean noCar = Double.isNaN(rpm) && Double.isNaN(speed);
            if (noCar) {
                empty++;
                if (empty == 4) listener.onStatus("nodata", "El coche no responde (¿contacto quitado?)");
                if (empty > 3) sleep(1500);
            } else {
                if (empty > 3) listener.onStatus("connected", "Conectado");
                empty = 0;
            }

            // Si no hay MAF, se calcula el aire con presión, temperatura y revoluciones.
            boolean estimated = false;
            if (Double.isNaN(maf) && !Double.isNaN(map) && !Double.isNaN(rpm)) {
                double t = (Double.isNaN(iat) ? 25 : iat) + 273.15;
                double ve = 0.85;
                maf = (map * 1000 / (287.05 * t)) * (displacement / 1000.0) * (rpm / 120.0) * ve * 1000;
                estimated = true;
            }

            double lph = Double.NaN;
            if (!Double.isNaN(maf)) {
                lph = maf * 3600.0 / (AFR * DENSITY) * correction;
                // Corte de inyección al soltar el acelerador con marcha metida: no gasta gasolina.
                if (!Double.isNaN(load) && load < 12 && !Double.isNaN(rpm) && rpm > 1300 && !Double.isNaN(speed) && speed > 15) lph = 0;
                if (Double.isNaN(rpm) || rpm < 300) lph = 0;
            }

            long now = System.currentTimeMillis();
            synchronized (this) {
                if (!noCar) {
                    if (now - tripLastData > 30 * 60 * 1000L) { tripKm = 0; tripLitres = 0; }  // viaje nuevo
                    if (lastSample > 0) {
                        double dt = Math.min(5, (now - lastSample) / 1000.0);
                        if (!Double.isNaN(speed)) tripKm += speed * dt / 3600.0;
                        if (!Double.isNaN(lph)) tripLitres += lph * dt / 3600.0;
                    }
                    tripLastData = now;
                    lastSample = now;
                } else {
                    lastSample = 0;
                }
            }

            JSONObject d = new JSONObject();
            try {
                put(d, "rpm", rpm);
                put(d, "speed", speed);
                put(d, "coolant", coolant);
                put(d, "volts", volts);
                put(d, "load", load);
                put(d, "maf", maf);
                put(d, "lph", lph);
                put(d, "fuelLevel", fuelLevel);
                if (!Double.isNaN(lph) && !Double.isNaN(speed) && speed >= 5) put(d, "l100", lph / speed * 100.0);
                d.put("estimated", estimated);
                double[] t = trip();
                d.put("tripKm", t[0]);
                d.put("tripL", t[1]);
                d.put("noCar", noCar);
            } catch (Exception ignored) { }
            listener.onData(d);
        }
    }

    private static void put(JSONObject o, String k, double v) throws Exception {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) o.put(k, Math.round(v * 100) / 100.0);
    }

    // ------------------------------------------------------------------ averías

    private void runJob(String job) throws Exception {
        JSONObject r = new JSONObject();
        if ("clear".equals(job)) {
            String res = send("04", 8000);
            boolean ok = res.toUpperCase().replaceAll("\\s", "").contains("44");
            r.put("cleared", ok);
            if (!ok) r.put("error", "El coche no aceptó el borrado. Pon el contacto con el motor APAGADO y prueba otra vez.");
            sleep(1500);
        }
        // Estado del testigo y número de averías (PID 01)
        int[] b = ObdParse.pidBytes(send("0101", 3000), 0x01);
        if (b != null && b.length >= 1) {
            r.put("mil", (b[0] & 0x80) != 0);
            r.put("count", b[0] & 0x7F);
        }
        r.put("stored", list(ObdParse.dtcs(send("03", 8000), 0x43, can)));
        r.put("pending", list(ObdParse.dtcs(send("07", 8000), 0x47, can)));
        if (can) r.put("permanent", list(ObdParse.dtcs(send("0A", 8000), 0x4A, can)));
        listener.onDtc(r);
    }

    private static JSONArray list(List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        return a;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
