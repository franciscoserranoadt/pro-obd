package es.proobd.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Busca versiones nuevas en GitHub y las instala con el instalador de Android (igual que Pro Maps y Pro Radio). */
final class Updater {

    static final String ACTION_STATUS = "es.proobd.app.INSTALL_STATUS";
    private static final String BASE = "https://github.com/franciscoserranoadt/pro-obd/releases/download/ultima/";

    static final class Info {
        final int code;
        final String name;
        final String notes;
        Info(int code, String name, String notes) { this.code = code; this.name = name; this.notes = notes; }
    }

    static int installedCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return (int) pi.getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            String n = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return n == null ? "?" : n;
        } catch (Exception e) {
            return "?";
        }
    }

    private static HttpURLConnection open(String url, int readTimeout) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(10000);
        con.setReadTimeout(readTimeout);
        con.setUseCaches(false);
        con.setRequestProperty("Cache-Control", "no-cache");
        con.setRequestProperty("User-Agent", "ProOBD-Updater");
        return con;
    }

    /** Versión publicada o null si no se puede consultar. Llamar desde un hilo secundario. */
    static Info latest() {
        HttpURLConnection con = null;
        try {
            con = open(BASE + "version.txt?t=" + System.currentTimeMillis(), 15000);
            if (con.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            }
            String txt = sb.toString().trim();
            int nl = txt.indexOf('\n');
            String first = nl >= 0 ? txt.substring(0, nl) : txt;
            String notes = nl >= 0 ? txt.substring(nl + 1).trim() : "";
            String[] parts = first.trim().split("\\s+");
            int code = Integer.parseInt(parts[0]);
            return new Info(code, parts.length > 1 ? parts[1] : String.valueOf(code), notes);
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Descarga la APK y abre la pantalla de instalar. Hilo secundario. Devuelve un error o null. */
    static String downloadAndInstall(Activity a) {
        HttpURLConnection con = null;
        try {
            con = open(BASE + "ProOBD.apk", 60000);
            if (con.getResponseCode() != 200) return "No se pudo descargar (error " + con.getResponseCode() + ")";
            PackageInstaller installer = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(a.getPackageName());
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (InputStream in = con.getInputStream();
                     OutputStream out = session.openWrite("ProOBD.apk", 0, con.getContentLengthLong())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent i = new Intent(a, MainActivity.class).setAction(ACTION_STATUS)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pi = PendingIntent.getActivity(a, 77, i, flags);
                session.commit(pi.getIntentSender());
            }
            return null;
        } catch (Exception e) {
            return "Error al actualizar: " + e.getMessage();
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
