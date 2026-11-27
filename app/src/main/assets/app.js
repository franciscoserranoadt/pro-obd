(function () {
  "use strict";
  var A = window.Android || null;          // puente con Java (no existe al probar en el ordenador)
  var $ = function (id) { return document.getElementById(id); };
  var state = "off", demoTimer = null, last = {}, logLines = [];

  function pref(k, d) {
    try { if (A) return A.getPref(k, d); } catch (e) {}
    try { var v = localStorage.getItem("obd_" + k); return v == null ? d : v; } catch (e) { return d; }
  }
  function setPref(k, v) {
    try { if (A) { A.setPref(k, String(v)); return; } } catch (e) {}
    try { localStorage.setItem("obd_" + k, String(v)); } catch (e) {}
  }
  function fmt(v, dec) { return v == null || isNaN(v) ? "--" : Number(v).toFixed(dec || 0).replace(".", ","); }

  // ------------------------------------------------------------ pestañas
  document.querySelectorAll(".tabs button").forEach(function (b) {
    b.onclick = function () { showTab(b.dataset.tab); };
  });
  function showTab(t) {
    document.querySelectorAll(".tabs button").forEach(function (b) { b.classList.toggle("active", b.dataset.tab === t); });
    document.querySelectorAll(".tab").forEach(function (s) { s.classList.toggle("active", s.id === "tab-" + t); });
    if (t === "cfg") refreshDevices();
  }
  $("status").onclick = function () { showTab("cfg"); };

  // ------------------------------------------------------------ avisos
  var toastT;
  function toast(msg) {
    var t = $("toast"); t.textContent = msg; t.classList.add("show");
    clearTimeout(toastT); toastT = setTimeout(function () { t.classList.remove("show"); }, 3200);
  }
  function confirmBox(title, text, yes, cb) {
    $("mTitle").textContent = title; $("mText").textContent = text; $("mYes").textContent = yes;
    $("modal").hidden = false;
    $("mNo").onclick = function () { $("modal").hidden = true; };
    $("mYes").onclick = function () { $("modal").hidden = true; cb(); };
  }

  // ------------------------------------------------------------ estado de la conexión
  function onStatus(s, msg) {
    if (demoOn() && s !== "demo") return;
    state = s;
    $("status").dataset.state = s;
    $("statusText").textContent = msg;
    if (s !== "connected" && s !== "demo" && s !== "nodata") clearValues();
    renderDevices();
  }
  function clearValues() {
    ["v-speed", "v-rpm", "v-temp", "v-fuel", "v-batt", "v-load", "v-tripL"].forEach(function (id) { $(id).textContent = "--"; });
    ["b-rpm", "b-temp"].forEach(function (id) { $(id).style.width = "0"; });
    ["c-temp", "c-batt", "c-rpm"].forEach(function (id) { $(id).className = "card"; });
    $("h-temp").textContent = ""; $("h-batt").textContent = "";
  }

  // ------------------------------------------------------------ datos del coche
  function onData(d) {
    if (d.info) {
      var i = d.info;
      $("infoText").textContent = "Adaptador: " + (i.adapter || "?") + " · Sensor de aire (MAF): " + (i.maf ? "sí" : "no, se calcula") +
        " · Datos que da el coche: " + (i.pids || []).length;
      return;
    }
    last = d;
    $("v-speed").textContent = fmt(d.speed);

    $("v-rpm").textContent = d.rpm == null ? "--" : Math.round(d.rpm / 10) * 10;
    var r = Math.min(100, (d.rpm || 0) / 70);
    $("b-rpm").style.width = r + "%";
    $("b-rpm").style.background = d.rpm > 5500 ? "var(--bad)" : d.rpm > 4000 ? "var(--warn)" : "var(--acc)";
    $("c-rpm").className = "card" + (d.rpm > 6000 ? " alert" : "");

    // Temperatura del motor
    var t = d.coolant, ct = $("c-temp"), ht = $("h-temp");
    $("v-temp").textContent = fmt(t);
    if (t == null) { ct.className = "card"; ht.textContent = ""; $("b-temp").style.width = "0"; }
    else {
      $("b-temp").style.width = Math.max(0, Math.min(100, (t - 30) / 1)) + "%";
      if (t >= 110) { ct.className = "card alert"; ht.textContent = "¡Muy caliente! Para el coche en un sitio seguro."; $("b-temp").style.background = "var(--bad)"; }
      else if (t >= 104) { ct.className = "card warn"; ht.textContent = "Más caliente de lo normal"; $("b-temp").style.background = "var(--warn)"; }
      else if (t < 70) { ct.className = "card cold"; ht.textContent = "Motor frío: no lo fuerces todavía"; $("b-temp").style.background = "var(--cold)"; }
      else { ct.className = "card good"; ht.textContent = "Temperatura normal"; $("b-temp").style.background = "var(--ok)"; }
    }

    // Consumo
    if (d.l100 != null) { $("l-fuel").textContent = "Consumo instantáneo"; $("v-fuel").textContent = fmt(Math.min(d.l100, 99.9), 1); $("u-fuel").textContent = "L/100 km"; }
    else if (d.lph != null) { $("l-fuel").textContent = "Consumo parado"; $("v-fuel").textContent = fmt(d.lph, 1); $("u-fuel").textContent = "L/hora"; }
    else { $("v-fuel").textContent = "--"; }
    var avg = d.tripKm > 0.5 ? d.tripL / d.tripKm * 100 : null;
    $("s-trip").textContent = "Viaje: " + fmt(avg, 1) + " L/100 · " + fmt(d.tripKm, 1) + " km" + (d.estimated ? " · aprox." : "");
    $("v-tripL").textContent = fmt(d.tripL, 2) + " L";
    $("v-load").textContent = d.load == null ? "--" : fmt(d.load) + " %";
    if (d.fuelLevel != null) { $("r-level").hidden = false; $("v-level").textContent = fmt(d.fuelLevel) + " %"; }

    // Batería
    var v = d.volts, cb = $("c-batt"), hb = $("h-batt");
    $("v-batt").textContent = fmt(v, 1);
    if (v == null) { cb.className = "card"; hb.textContent = ""; }
    else {
      var running = (d.rpm || 0) > 400;
      if (running) {
        if (v >= 13.2 && v <= 14.9) { cb.className = "card good"; hb.textContent = "El alternador carga bien"; }
        else if (v > 14.9) { cb.className = "card alert"; hb.textContent = "Carga demasiado alta: revisa el alternador"; }
        else { cb.className = "card warn"; hb.textContent = "Carga baja con el motor en marcha"; }
      } else {
        if (v >= 12.4) { cb.className = "card good"; hb.textContent = "Batería bien"; }
        else if (v >= 12.0) { cb.className = "card warn"; hb.textContent = "Batería algo baja"; }
        else { cb.className = "card alert"; hb.textContent = "Batería baja: puede que no arranque"; }
      }
    }
  }

  $("btnTrip").onclick = function () {
    confirmBox("Reiniciar viaje", "Se ponen a cero los kilómetros y los litros del viaje.", "Reiniciar", function () {
      if (A) A.resetTrip(); demoTrip = { km: 0, l: 0 }; toast("Viaje a cero");
    });
  };

  // ------------------------------------------------------------ averías
  function canTalk() {
    if (demoOn()) return true;
    if (state !== "connected" && state !== "nodata") { toast("Primero conecta el adaptador"); return false; }
    return true;
  }
  $("btnRead").onclick = function () {
    if (!canTalk()) return;
    $("milText").textContent = "Leyendo averías…";
    $("dtcList").innerHTML = "";
    if (demoOn()) { setTimeout(function () { onDtc({ mil: true, count: 2, stored: ["P0133", "P0420"], pending: ["P0171"], permanent: [] }); }, 800); return; }
    A.readDtc();
  };
  $("btnClear").onclick = function () {
    if (!canTalk()) return;
    confirmBox("¿Borrar las averías?",
      "1. Pon el contacto con el motor APAGADO.\n2. El testigo de avería se apagará, pero si el fallo sigue, volverá a salir.\n3. Durante unos días el coche no estará «listo» para pasar la ITV (hay que circular para que se rehagan las pruebas).",
      "Sí, borrar", function () {
        $("milText").textContent = "Borrando…";
        if (demoOn()) { setTimeout(function () { onDtc({ cleared: true, mil: false, count: 0, stored: [], pending: [], permanent: [] }); }, 800); return; }
        A.clearDtc();
      });
  };
  function onDtc(r) {
    if (r.error) toast(r.error);
    else if (r.cleared) toast("Averías borradas");
    var all = [];
    (r.stored || []).forEach(function (c) { all.push({ c: c, k: "", t: "Guardada" }); });
    (r.pending || []).forEach(function (c) { all.push({ c: c, k: "pending", t: "Pendiente" }); });
    (r.permanent || []).forEach(function (c) { all.push({ c: c, k: "permanent", t: "Permanente" }); });
    var mil = r.mil ? "Testigo de avería del motor ENCENDIDO." : "Testigo de avería apagado.";
    $("milText").textContent = mil + " " + (all.length ? all.length + " código(s) encontrado(s)." : "");
    var badge = $("dtcBadge");
    badge.hidden = all.length === 0; badge.textContent = all.length;
    if (!all.length) {
      $("dtcList").innerHTML = '<div class="empty"><b>✓</b>No hay ninguna avería guardada en el motor.</div>';
      return;
    }
    $("dtcList").innerHTML = all.map(function (x) {
      return '<div class="dtc ' + x.k + '"><span class="code">' + x.c + '</span><span class="tag">' + x.t + '</span><div class="desc">' +
        dtcText(x.c) + '</div></div>';
    }).join("") + '<p class="muted" style="grid-column:1/-1">Guardada: el fallo se ha confirmado. Pendiente: se ha visto una vez y el coche lo está vigilando. Permanente: solo se borra sola cuando el coche comprueba que está arreglado.</p>';
  }

  // ------------------------------------------------------------ adaptadores
  var devices = [];
  function refreshDevices() {
    try { devices = A ? JSON.parse(A.paired()) : []; } catch (e) { devices = []; }
    devices.sort(function (a, b) { return (b.obd ? 1 : 0) - (a.obd ? 1 : 0); });
    renderDevices();
  }
  function renderDevices() {
    var sel = A ? A.device() : "";
    var box = $("devList");
    if (!devices.length) {
      box.innerHTML = '<p class="muted">No hay aparatos Bluetooth emparejados. Pulsa «Abrir Bluetooth», empareja tu adaptador (suele llamarse OBDII, OBD2 o V-LINK) y vuelve aquí.</p>';
      return;
    }
    box.innerHTML = "";
    devices.forEach(function (d) {
      var b = document.createElement("button");
      b.className = "dev" + (d.address === sel ? " sel" : "");
      var tag = d.address === sel ? ($("statusText").textContent) : (d.obd ? "Parece un OBD · tocar para usar" : "Tocar para usar");
      b.innerHTML = '<span>' + escapeHtml(d.name) + '<small>' + d.address + '</small></span><em>' + escapeHtml(tag) + '</em>';
      b.onclick = function () {
        if (demoOn()) { $("demo").checked = false; setDemo(false); }
        A.connect(d.address); toast("Conectando con " + d.name + "…"); setTimeout(refreshDevices, 400);
      };
      box.appendChild(b);
    });
  }
  function escapeHtml(s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  $("btnRefresh").onclick = refreshDevices;
  $("btnBt").onclick = function () { if (A) A.openBtSettings(); };
  $("btnDisc").onclick = function () { if (A) A.disconnect(); setTimeout(refreshDevices, 300); };

  // ------------------------------------------------------------ motor y corrección
  function renderEngine() {
    var e = pref("engine", "1.2");
    document.querySelectorAll("#engine button").forEach(function (b) { b.classList.toggle("on", b.dataset.v === e); });
  }
  document.querySelectorAll("#engine button").forEach(function (b) {
    b.onclick = function () { setPref("engine", b.dataset.v); renderEngine(); syCfgChanged(); };
  });
  function renderCorr() {
    var c = parseFloat(pref("correction", "1")) || 1;
    $("corrText").textContent = "Corrección actual: " + (c >= 1 ? "+" : "") + Math.round((c - 1) * 100) + " %";
  }
  $("btnCal").onclick = function () {
    var real = parseFloat(String($("realL").value).replace(",", "."));
    var app = last.tripL;
    if (!(real > 1)) { toast("Escribe los litros que has echado"); return; }
    if (!(app > 1)) { toast("La app aún no ha contado bastantes litros en este viaje"); return; }
    var c = (parseFloat(pref("correction", "1")) || 1) * real / app;
    c = Math.max(0.6, Math.min(1.4, c));
    setPref("correction", c.toFixed(3));
    renderCorr(); syCfgChanged();
    $("realL").value = "";
    toast("Consumo corregido");
  };

  // ------------------------------------------------------------ registro del adaptador
  $("logOn").onchange = function () {
    var on = $("logOn").checked; $("log").hidden = !on;
    if (A) A.setLog(on);
  };
  function onLog(line) {
    logLines.push(line); if (logLines.length > 60) logLines.shift();
    $("log").textContent = logLines.join("\n"); $("log").scrollTop = 1e6;
  }

  // ------------------------------------------------------------ modo demostración
  var demoTrip = { km: 12.4, l: 0.86 }, demoT = 0;
  function demoOn() { return $("demo").checked; }
  function setDemo(on) {
    setPref("demo", on ? "1" : "0");
    clearInterval(demoTimer);
    if (on) {
      state = "demo"; $("status").dataset.state = "demo"; $("statusText").textContent = "Modo demostración";
      demoTimer = setInterval(demoTick, 500); demoTick();
    } else {
      $("statusText").textContent = "Sin conectar"; $("status").dataset.state = "off"; clearValues();
      if (A) { var dev = A.device(); if (dev) A.connect(dev); }
    }
  }
  function demoTick() {
    demoT += 0.5;
    var speed = Math.max(0, 62 + 38 * Math.sin(demoT / 9) + 6 * Math.sin(demoT / 2));
    var rpm = speed < 2 ? 780 : 1300 + speed * 22 + 300 * Math.sin(demoT / 3);
    var lph = speed < 2 ? 0.7 : 2 + speed * 0.055 + 1.5 * Math.max(0, Math.sin(demoT / 3));
    demoTrip.km += speed * 0.5 / 3600; demoTrip.l += lph * 0.5 / 3600;
    onData({ speed: speed, rpm: rpm, coolant: Math.min(91, 60 + demoT * 0.6), volts: 14.1, load: 35 + 20 * Math.sin(demoT / 4),
      lph: lph, l100: speed >= 5 ? lph / speed * 100 : null, tripKm: demoTrip.km, tripL: demoTrip.l });
  }
  $("demo").onchange = function () { setDemo($("demo").checked); };

  // ------------------------------------------------------------ versión
  $("btnUpd").onclick = function () { if (A) A.checkUpdate(); else toast("Solo dentro de la app"); };
  $("verText").textContent = "Versión " + (A ? A.version() : "de prueba");

  // ------------------------------------------------------------ arranque
  // ------------------------------------------------------------ Código Pro: móvil ⇄ coche (prosync.js)
  // Se sincronizan el motor y la corrección, la última lectura de averías y los km que cuenta la app
  // (Pro Garaje los usa para saber los km del coche).
  function lsJ(k, d) { try { var v = localStorage.getItem("obd_sy_" + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } }
  function lsS(k, v) { try { localStorage.setItem("obd_sy_" + k, JSON.stringify(v)); } catch (e) {} }
  var SY = { cfgT: lsJ("cfgT", 0), dtc: lsJ("dtc", null), odo: lsJ("odo", { km: 0, t: 0 }) };
  function syGet() {
    return { cfg: { engine: pref("engine", "1.2"), correction: String(parseFloat(pref("correction", "1")) || 1), t: SY.cfgT }, dtc: SY.dtc, odo: SY.odo };
  }
  function canon(x) { return window.PS ? PS.canon(x) : JSON.stringify(x); }
  function newer(a, b) { if (!a) return b; if (!b) return a; if ((a.t || 0) !== (b.t || 0)) return (a.t || 0) > (b.t || 0) ? a : b; return canon(a) >= canon(b) ? a : b; }
  function syMerge(L, R) {
    var o = { km: Math.max((L.odo || {}).km || 0, (R.odo || {}).km || 0), t: Math.max((L.odo || {}).t || 0, (R.odo || {}).t || 0) };
    return { cfg: newer(L.cfg, R.cfg), dtc: newer(L.dtc, R.dtc), odo: o };
  }
  function syApply(m) {
    var msg = "";
    if (m.cfg && (m.cfg.t || 0) > SY.cfgT) {
      setPref("engine", m.cfg.engine); setPref("correction", m.cfg.correction);
      SY.cfgT = m.cfg.t; lsS("cfgT", SY.cfgT); renderEngine(); renderCorr(); msg = "Ajustes recibidos del otro aparato";
    }
    if (m.dtc && (!SY.dtc || (m.dtc.t || 0) > (SY.dtc.t || 0))) {
      SY.dtc = m.dtc; lsS("dtc", SY.dtc); showDtc(SY.dtc, true); msg = "Lectura de averías recibida del otro aparato";
    }
    if (m.odo && m.odo.km > (SY.odo.km || 0)) {
      if (A && A.odoAtLeast) A.odoAtLeast(String(m.odo.km));
    }
    if (m.odo) { SY.odo = m.odo; lsS("odo", SY.odo); }
    if (msg) toast("📲 " + msg);
  }
  function syCfgChanged() { SY.cfgT = Date.now(); lsS("cfgT", SY.cfgT); if (window.PS) PS.changed("obd"); }
  var odoPushT = 0;
  function syOdo(km, force) {
    if (!(km >= 0) || km <= (SY.odo.km || 0) + 0.05) return;
    if (!force && km - (SY.odo.km || 0) < 0.5 && Date.now() - odoPushT < 600000) return;
    if (!force && Date.now() - odoPushT < 180000) return;  // como mucho cada 3 minutos conduciendo
    odoPushT = Date.now();
    SY.odo = { km: Math.round(km * 100) / 100, t: Date.now() }; lsS("odo", SY.odo);
    if (window.PS) PS.changed("obd", 2000);
  }
  function fmtDate(t) { var d = new Date(t); return d.toLocaleDateString("es-ES") + " " + d.toLocaleTimeString("es-ES", { hour: "2-digit", minute: "2-digit" }); }
  function showDtc(r, remote) {
    onDtc(r.cleared ? { mil: r.mil, stored: [], pending: [], permanent: [] } : r);
    var p = document.createElement("p"); p.className = "muted"; p.style.gridColumn = "1/-1";
    p.textContent = "Última lectura: " + fmtDate(r.t) + (remote ? " (en el otro aparato)" : "");
    $("dtcList").appendChild(p);
  }
  if (window.PS) {
    PS.register("obd", {
      get: syGet, merge: syMerge, apply: syApply,
      hasData: function (d) { return d.cfg.t > 0 || !!d.dtc || d.odo.km > 0; }
    });
    var hold = $("psHold");
    if (hold) hold.appendChild(PS.box({ toast: toast, what: "el motor y la corrección del consumo, la última lectura de averías y los km que cuenta Pro OBD (Pro Garaje los usa para los km del coche)." }));
  }
  if (SY.dtc) showDtc(SY.dtc, false);

  window.OBD = { onStatus: function (s, m) { onStatus(s, m); if (s !== "connected" && s !== "demo" && A && A.odo) syOdo(parseFloat(A.odo()), true); },
    onData: function (d) { if (!demoOn() || d.info) { onData(d); if (!d.info && !d.noCar && d.odoKm != null) syOdo(d.odoKm, false); } },
    onDtc: function (r) {
      if (!demoOn() && !r.error) {
        SY.dtc = { t: Date.now(), mil: !!r.mil, cleared: !!r.cleared, stored: r.stored || [], pending: r.pending || [], permanent: r.permanent || [] };
        lsS("dtc", SY.dtc); if (window.PS) PS.changed("obd", 1000);
        showDtc(SY.dtc, false); if (r.cleared) toast("Averías borradas");
        return;
      }
      onDtc(r);
    },
    onLog: onLog,
    toast: toast, refreshDevices: refreshDevices, showTab: showTab };
  renderEngine(); renderCorr(); refreshDevices();
  if (pref("demo", "0") === "1") { $("demo").checked = true; setDemo(true); }
})();
