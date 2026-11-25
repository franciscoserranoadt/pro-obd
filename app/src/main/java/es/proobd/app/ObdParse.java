package es.proobd.app;

import java.util.ArrayList;
import java.util.List;

/** Lee las respuestas de texto del adaptador ELM327. Sin nada de Android, para poder probarlo aparte. */
final class ObdParse {

    private ObdParse() {}

    /** Quita el eco, «SEARCHING...», espacios y el símbolo «>». Devuelve las líneas útiles en mayúsculas. */
    static List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String r = raw.toUpperCase().replace(">", "").replace("SEARCHING...", "").replace("BUS INIT: ...", "");
        for (String l : r.split("[\r\n]+")) {
            String s = l.replaceAll("\\s", "");
            if (s.isEmpty() || s.equals("OK")) continue;
            out.add(s);
        }
        return out;
    }

    /** ¿La respuesta es un error o «sin datos»? */
    static boolean isError(String raw) {
        if (raw == null) return true;
        String r = raw.toUpperCase();
        return r.contains("NODATA") || r.contains("NO DATA") || r.contains("UNABLE") || r.contains("ERROR")
                || r.contains("STOPPED") || r.contains("?") || r.contains("BUSINIT") || r.contains("BUS INIT")
                || r.contains("CAN ERROR") || r.contains("BUFFER FULL") || r.trim().replace(">", "").isEmpty();
    }

    /** Bytes de datos que siguen a «41 XX» (respuesta del modo 01), o null. */
    static int[] pidBytes(String raw, int pid) {
        String key = String.format("41%02X", pid);
        for (String l : lines(raw)) {
            int idx = l.indexOf(key);
            if (idx < 0) continue;
            String hex = l.substring(idx + 4);
            int n = hex.length() / 2;
            int[] b = new int[n];
            try {
                for (int i = 0; i < n; i++) b[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                continue;
            }
            return b;
        }
        return null;
    }

    /** Voltaje de «AT RV» (por ejemplo «12.6V»), o NaN. */
    static double volts(String raw) {
        if (raw == null) return Double.NaN;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}[.,]\\d)\\s*V").matcher(raw.toUpperCase());
        if (!m.find()) return Double.NaN;
        try {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** Marca en «supported» los PID que dice la respuesta de 0100 / 0120 / 0140. */
    static boolean supportedPids(String raw, int base, boolean[] supported) {
        int[] b = pidBytes(raw, base);
        if (b == null || b.length < 4) return false;
        for (int i = 0; i < 32; i++) {
            int byteIdx = i / 8, bit = 7 - (i % 8);
            if (((b[byteIdx] >> bit) & 1) == 1) {
                int pid = base + i + 1;
                if (pid < supported.length) supported[pid] = true;
            }
        }
        return true;
    }

    /**
     * Saca los códigos de avería de la respuesta del modo 03 (guardados), 07 (pendientes) o 0A (permanentes).
     * «mode» es el byte de respuesta: 0x43, 0x47 o 0x4A. «can» indica si el coche usa CAN (el Qashqai J11, sí).
     */
    static List<String> dtcs(String raw, int mode, boolean can) {
        List<String> codes = new ArrayList<>();
        if (raw == null) return codes;
        String head = String.format("%02X", mode);

        // Junta los mensajes de varias tramas («0:» «1:» …) y separa los de cada centralita.
        List<StringBuilder> msgs = new ArrayList<>();
        StringBuilder cur = null;
        for (String l : lines(raw)) {
            if (l.matches("[0-9A-F]{3}")) { cur = null; continue; }           // longitud de un mensaje largo
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([0-9A-F]):(.*)$").matcher(l);
            if (m.find()) {
                if (m.group(1).equals("0") || cur == null) { cur = new StringBuilder(); msgs.add(cur); }
                cur.append(m.group(2));
            } else {
                cur = new StringBuilder(l);
                msgs.add(cur);
                cur = null;
            }
        }

        for (StringBuilder sb : msgs) {
            String s = sb.toString().replaceAll("[^0-9A-F]", "");
            int idx = s.indexOf(head);
            if (idx < 0) continue;
            String data = s.substring(idx + 2);
            int max = Integer.MAX_VALUE;
            if (can && data.length() >= 2) {
                try {
                    max = Integer.parseInt(data.substring(0, 2), 16);
                } catch (NumberFormatException e) {
                    max = 0;
                }
                data = data.substring(2);
            }
            int found = 0;
            for (int i = 0; i + 4 <= data.length() && found < max; i += 4) {
                String c = data.substring(i, i + 4);
                if (c.equals("0000")) continue;
                String code = decode(c);
                if (code != null && !codes.contains(code)) codes.add(code);
                found++;
            }
        }
        return codes;
    }

    /** «0133» → «P0133», «C123» → «U0123», etc. */
    static String decode(String four) {
        try {
            int v = Integer.parseInt(four.substring(0, 1), 16);
            char type = "PCBU".charAt(v >> 2);
            return type + Integer.toString(v & 3) + four.substring(1);
        } catch (Exception e) {
            return null;
        }
    }
}
