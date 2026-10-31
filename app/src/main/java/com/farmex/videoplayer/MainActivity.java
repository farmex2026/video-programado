package com.farmex.videoplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int MAX = 5;
    private WebView web;
    private PlayerView pv;
    private ExoPlayer exo;
    private SharedPreferences p;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean playing = false;
    private volatile int gen = 0;
    private int syncCount = 0;
    private static final String BASE = "https://farmex2026.github.io/";
    private static final String LOCAL = BASE + "__v/";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (syncCount++ % 9 == 0) sync();
            check();
            h.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("cfg", MODE_PRIVATE);
        // Pasar la URL de la versión anterior al casillero 1
        if (!p.getString("url", "").isEmpty() && p.getString("url1", "").isEmpty()) {
            p.edit().putString("url1", p.getString("url", "")).remove("url").apply();
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith(LOCAL)) return serveLocal(u.substring(LOCAL.length()), r.getRequestHeaders());
                return null;
            }
        });
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(new CookieManager());
        web.setWebChromeClient(new WebChromeClient());
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        pv.setShutterBackgroundColor(Color.BLACK);
        pv.setBackgroundColor(Color.BLACK);
        pv.setFocusable(false);
        pv.setVisibility(View.GONE);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        hideBars();
        if (urls().isEmpty()) showSettings();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() {
        super.onResume();
        hideBars();
        playing = false;
        syncCount = 0;
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private List<String> urls() {
        List<String> l = new ArrayList<>();
        for (int i = 1; i <= MAX; i++) {
            String u = p.getString("url" + i, "").trim();
            if (!u.isEmpty()) l.add(fixDrive(u));
        }
        return l;
    }

    private void check() {
        List<String> l = urls();
        if (l.isEmpty()) return;
        boolean on = inSchedule();
        if (on && !playing) play(l);
        else if (!on && playing) stop();
    }

    private boolean inSchedule() {
        if (!p.getBoolean("sched", false)) return true;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int a = p.getInt("start", 480), e = p.getInt("end", 1320);
        if (a == e) return true;
        if (a < e) return now >= a && now < e;
        return now >= a || now < e;
    }

    private void play(List<String> l) {
        playing = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final int g = ++gen;
        releaseExo();
        String first = l.get(0);
        if (l.size() == 1 && !isYoutube(first) && !isVideo(first)) {
            web.loadUrl(first); // una sola página web: se abre tal cual
            return;
        }
        final List<String> media = new ArrayList<>();
        for (String u : l) if (isYoutube(u) || isVideo(u)) media.add(u);
        if (media.isEmpty()) { web.loadUrl(first); return; }
        status("Preparando videos...");
        new Thread(() -> {
            File dir = videoDir();
            List<String> fin = new ArrayList<>();
            List<Uri> uris = new ArrayList<>();
            boolean anyYT = false;
            Set<String> keep = new HashSet<>();
            int total = 0, k = 0;
            for (String u : media) if (!isYoutube(u)) total++;
            for (String u : media) {
                if (g != gen) return;
                if (isYoutube(u)) { fin.add(u); anyYT = true; continue; }
                k++;
                String name = Integer.toHexString(u.hashCode()) + ".mp4";
                keep.add(name);
                File f = new File(dir, name);
                if (f.exists() || download(u, f, k, total, g)) { fin.add(LOCAL + name); uris.add(Uri.fromFile(f)); }
                else { fin.add(u); uris.add(Uri.parse(u)); } // si no se pudo bajar, se intenta online
            }
            if (g != gen) return;
            File[] all = dir.listFiles();
            if (all != null) for (File x : all) if (!keep.contains(x.getName())) x.delete();
            final boolean native_ = !anyYT;
            runOnUiThread(() -> {
                if (g != gen || !playing) return;
                if (native_) { playNative(uris); return; }
                String list = new JSONArray(fin).toString().replace("</", "<\\/");
                web.loadDataWithBaseURL(BASE, PLAYER.replace("__LIST__", list), "text/html", "utf-8", null);
            });
        }).start();
    }

    // Reproductor nativo de Android: lista en loop, el siguiente ya precargado
    private void playNative(List<Uri> uris) {
        releaseExo();
        exo = new ExoPlayer.Builder(this).build();
        pv.setPlayer(exo);
        for (Uri u : uris) exo.addMediaItem(MediaItem.fromUri(u));
        exo.setRepeatMode(Player.REPEAT_MODE_ALL);
        exo.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                Toast.makeText(MainActivity.this, "Error con un video, paso al siguiente", Toast.LENGTH_LONG).show();
                h.postDelayed(() -> {
                    if (exo == null) return;
                    if (exo.hasNextMediaItem()) exo.seekToNextMediaItem();
                    else exo.seekToDefaultPosition(0);
                    exo.prepare();
                    exo.play();
                }, 3000);
            }
        });
        exo.prepare();
        exo.play();
        web.loadUrl("about:blank");
        web.setVisibility(View.GONE);
        pv.setVisibility(View.VISIBLE);
    }

    private void releaseExo() {
        if (exo != null) { pv.setPlayer(null); exo.release(); exo = null; }
        pv.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        releaseExo();
    }

    // ---------- Panel web: trae los videos y horario de esta TV ----------
    private static int hm(String t, int def) {
        try { String[] a = t.split(":"); return Integer.parseInt(a[0].trim()) * 60 + Integer.parseInt(a[1].trim()); }
        catch (Exception e) { return def; }
    }

    private void sync() {
        final String panel = p.getString("panel", "").trim();
        if (panel.isEmpty()) return;
        final int tv = p.getInt("tv", 1);
        new Thread(() -> {
            try {
                HttpURLConnection c = open(panel + (panel.contains("?") ? "&" : "?") + "tv=" + tv);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                JSONArray a = j.optJSONArray("urls");
                if (a == null) return;
                SharedPreferences.Editor e = p.edit();
                boolean changed = false;
                for (int i = 0; i < MAX; i++) {
                    String u = i < a.length() ? a.optString(i, "").trim() : "";
                    if (!u.equals(p.getString("url" + (i + 1), ""))) changed = true;
                    e.putString("url" + (i + 1), u);
                }
                boolean sc = j.optBoolean("sched", false);
                int st = hm(j.optString("start", "08:00"), 480), en = hm(j.optString("end", "22:00"), 1320);
                if (sc != p.getBoolean("sched", false) || st != p.getInt("start", 480) || en != p.getInt("end", 1320)) changed = true;
                e.putBoolean("sched", sc).putInt("start", st).putInt("end", en);
                int rev = j.optInt("rev", 0);
                final boolean redl = p.contains("rev") && rev != p.getInt("rev", 0);
                e.putInt("rev", rev).apply();
                if (changed || redl) {
                    runOnUiThread(() -> {
                        stop();
                        if (redl) { File[] all = videoDir().listFiles(); if (all != null) for (File x : all) x.delete(); }
                        check();
                    });
                }
            } catch (Exception ignored) {
                // sin internet: sigue con lo último que tenía
            }
        }).start();
    }

    private void status(String t) {
        runOnUiThread(() -> web.loadDataWithBaseURL(BASE,
                "<html><body style='margin:0;background:#000;color:#ccc;font:18px sans-serif'>"
                + "<div style='position:absolute;left:12px;bottom:12px'>" + t + "</div></body></html>",
                "text/html", "utf-8", null));
    }

    private File videoDir() {
        File d = new File(getFilesDir(), "videos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        return c;
    }

    // Baja el video al equipo (sirve para Drive y links directos)
    private boolean download(String url, File out, int k, int total, int g) {
        File part = new File(out.getPath() + ".part");
        try {
            HttpURLConnection c = open(url);
            String ct = c.getContentType();
            if (ct != null && ct.startsWith("text/html")) {
                // Drive mostró la página de aviso: se arma el link de confirmación
                InputStream hin = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] hb = new byte[8192]; int hn;
                while ((hn = hin.read(hb)) > 0) bo.write(hb, 0, hn);
                hin.close();
                String html = bo.toString("UTF-8");
                Matcher a = Pattern.compile("action=\"([^\"]+)\"").matcher(html);
                if (!a.find()) return false;
                StringBuilder q = new StringBuilder(a.group(1).replace("&amp;", "&"));
                Matcher in = Pattern.compile("name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"").matcher(html);
                char sep = q.indexOf("?") < 0 ? '?' : '&';
                while (in.find()) {
                    q.append(sep).append(in.group(1)).append('=').append(URLEncoder.encode(in.group(2), "UTF-8"));
                    sep = '&';
                }
                c = open(q.toString());
                ct = c.getContentType();
                if (ct != null && ct.startsWith("text/html")) return false;
            }
            long len = -1;
            try { len = Long.parseLong(c.getHeaderField("Content-Length")); } catch (Exception ignored) {}
            long done = 0, lastShow = 0;
            try (InputStream is = c.getInputStream(); FileOutputStream os = new FileOutputStream(part)) {
                byte[] b = new byte[65536]; int n;
                while ((n = is.read(b)) > 0) {
                    if (g != gen) { os.close(); part.delete(); return false; }
                    os.write(b, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastShow > 1500) {
                        lastShow = now;
                        String pc = len > 0 ? " " + (done * 100 / len) + "%" : " " + (done / 1048576) + " MB";
                        status("Descargando video " + k + " de " + total + "..." + pc);
                    }
                }
            }
            if (done < 10000) { part.delete(); return false; }
            return part.renameTo(out);
        } catch (Exception e) {
            part.delete();
            return false;
        }
    }

    // Entrega el video guardado al reproductor (con soporte para saltar dentro del archivo)
    private WebResourceResponse serveLocal(String name, Map<String, String> reqHeaders) {
        try {
            int qi = name.indexOf('?');
            if (qi >= 0) name = name.substring(0, qi);
            File f = new File(videoDir(), name.replaceAll("[^A-Za-z0-9.]", ""));
            if (!f.exists()) return null;
            long len = f.length(), start = 0, end = len - 1;
            String range = null;
            for (Map.Entry<String, String> e : reqHeaders.entrySet())
                if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String[] r = range.substring(6).split("-");
                if (!r[0].isEmpty()) start = Long.parseLong(r[0].trim());
                if (r.length > 1 && !r[1].trim().isEmpty()) end = Math.min(Long.parseLong(r[1].trim()), len - 1);
                partial = true;
            }
            final long count = end - start + 1;
            final FileInputStream fis = new FileInputStream(f);
            long skipped = 0;
            while (skipped < start) skipped += fis.skip(start - skipped);
            InputStream limited = new InputStream() {
                long left = count;
                @Override public int read() throws IOException {
                    if (left <= 0) return -1;
                    int x = fis.read(); if (x >= 0) left--; return x;
                }
                @Override public int read(byte[] b, int off, int l) throws IOException {
                    if (left <= 0) return -1;
                    int x = fis.read(b, off, (int) Math.min(l, left));
                    if (x > 0) left -= x;
                    return x;
                }
                @Override public void close() throws IOException { fis.close(); }
            };
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Content-Length", String.valueOf(count));
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Cache-Control", "no-store");
            if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            return new WebResourceResponse("video/mp4", null, partial ? 206 : 200,
                    partial ? "Partial Content" : "OK", h, limited);
        } catch (Exception e) {
            return null;
        }
    }

    private void stop() {
        playing = false;
        gen++;
        releaseExo();
        web.loadUrl("about:blank");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private String fixDrive(String url) {
        Matcher m = Pattern.compile("drive\\.google\\.com/(?:file/d/|open\\?id=)([\\w-]+)").matcher(url);
        if (m.find()) return "https://drive.usercontent.google.com/download?id=" + m.group(1) + "&export=download&confirm=t";
        return url;
    }

    private boolean isYoutube(String u) {
        return u.contains("youtube.com") || u.contains("youtu.be");
    }

    private boolean isVideo(String u) {
        String l = u.toLowerCase(Locale.US);
        return l.contains("drive.google.com/uc") || l.contains("drive.usercontent.google.com") || l.matches(".*\\.(mp4|webm|mkv|mov)(\\?.*)?$");
    }

    private static String fmt(int m) {
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    @Override public void onBackPressed() { showSettings(); }

    @Override public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_MENU) { showSettings(); return true; }
        return super.onKeyDown(code, ev);
    }

    private void showSettings() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 20, 40, 0);

        TextView tp1 = new TextView(this);
        tp1.setText("Link del panel web (si lo cargás, los videos y el horario se toman de ahí):");
        ll.addView(tp1);
        final EditText panelEt = new EditText(this);
        panelEt.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        panelEt.setSingleLine(true);
        panelEt.setHint("https://script.google.com/macros/s/.../exec");
        panelEt.setText(p.getString("panel", ""));
        ll.addView(panelEt);
        TextView tp2 = new TextView(this);
        tp2.setText("Número de esta TV:");
        ll.addView(tp2);
        final EditText tvEt = new EditText(this);
        tvEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        tvEt.setSingleLine(true);
        tvEt.setText(String.valueOf(p.getInt("tv", 1)));
        ll.addView(tvEt);

        final EditText[] ets = new EditText[MAX];
        for (int i = 0; i < MAX; i++) {
            TextView t = new TextView(this);
            t.setText("Video " + (i + 1) + ":");
            ll.addView(t);
            ets[i] = new EditText(this);
            ets[i].setInputType(InputType.TYPE_TEXT_VARIATION_URI);
            ets[i].setSingleLine(true);
            ets[i].setHint("URL (opcional)");
            ets[i].setText(p.getString("url" + (i + 1), ""));
            ll.addView(ets[i]);
        }

        final CheckBox redl = new CheckBox(this);
        redl.setText("Volver a descargar los videos (si cambiaste el video en Drive)");
        ll.addView(redl);

        final CheckBox cb = new CheckBox(this);
        cb.setText("Usar horario");
        cb.setChecked(p.getBoolean("sched", false));
        ll.addView(cb);

        final int[] st = { p.getInt("start", 480) };
        final int[] en = { p.getInt("end", 1320) };
        final Button bs = new Button(this);
        final Button be = new Button(this);
        bs.setText("Inicia: " + fmt(st[0]));
        be.setText("Termina: " + fmt(en[0]));
        bs.setOnClickListener(v -> new TimePickerDialog(this, (tp, hh, mm) -> {
            st[0] = hh * 60 + mm; bs.setText("Inicia: " + fmt(st[0]));
        }, st[0] / 60, st[0] % 60, true).show());
        be.setOnClickListener(v -> new TimePickerDialog(this, (tp, hh, mm) -> {
            en[0] = hh * 60 + mm; be.setText("Termina: " + fmt(en[0]));
        }, en[0] / 60, en[0] % 60, true).show());
        ll.addView(bs);
        ll.addView(be);

        ScrollView sv = new ScrollView(this);
        sv.addView(ll);

        new AlertDialog.Builder(this)
                .setTitle("Configuración")
                .setView(sv)
                .setPositiveButton("Guardar", (d, w) -> {
                    SharedPreferences.Editor e = p.edit();
                    int tvn = 1;
                    try { tvn = Integer.parseInt(tvEt.getText().toString().trim()); } catch (Exception ignored) {}
                    e.putString("panel", panelEt.getText().toString().trim()).putInt("tv", tvn);
                    for (int i = 0; i < MAX; i++) e.putString("url" + (i + 1), ets[i].getText().toString().trim());
                    e.putBoolean("sched", cb.isChecked()).putInt("start", st[0]).putInt("end", en[0]).apply();
                    stop();
                    if (redl.isChecked()) {
                        File[] all = videoDir().listFiles();
                        if (all != null) for (File x : all) x.delete();
                    }
                    syncCount = 0;
                    sync();
                    check();
                    hideBars();
                })
                .setNegativeButton("Cancelar", (d, w) -> hideBars())
                .setNeutralButton("Salir", (d, w) -> finish())
                .show();
    }

    // Reproductor con dos capas: mientras suena un video, el siguiente ya está
    // cargado y pausado detrás; al terminar se cambia de capa sin corte.
    private static final String PLAYER = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;height:100%;background:#000;overflow:hidden}
.ly{position:absolute;top:0;left:0;width:100%;height:100%;opacity:0;z-index:1;background:#000}
#st{position:absolute;left:12px;bottom:12px;z-index:9;color:#ccc;font:16px sans-serif;background:rgba(0,0,0,.6);padding:6px 10px;border-radius:6px}
.ly video,.ly iframe,.ly div{width:100%;height:100%;border:0;object-fit:contain;background:#000}
</style></head><body>
<div class="ly" id="a"></div><div class="ly" id="b"></div><div id="st">Cargando video 1...</div>
<script>
var LIST=__LIST__;
var ST=document.getElementById('st');
function msg(t){ST.textContent=t;ST.style.display=t?'block':'none';}
var YTERR={2:'link de YouTube inválido',5:'error del reproductor',100:'video eliminado o privado',101:'el dueño no permite verlo fuera de YouTube',150:'el dueño no permite verlo fuera de YouTube',152:'YouTube bloqueó el reproductor',153:'YouTube bloqueó el reproductor'};
var RX=new RegExp("(?:youtu[.]be/|[?&]v=|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");
var items=LIST.map(function(u){var m=u.match(RX);return m?{t:'yt',id:m[1]}:{t:'v',src:u};});
var layers=[document.getElementById('a'),document.getElementById('b')];
var loadN=0,endedSince=0,slots=[null,null],cur=0,switching=false,ytReady=false,pend=[],lastT=-1,lastMove=Date.now();

function restart(){endedSince=0;destroy(0);destroy(1);switching=false;lastMove=Date.now();first(0);}
function needYT(){return items.some(function(i){return i.t=='yt';});}
function onYouTubeIframeAPIReady(){ytReady=true;pend.forEach(function(f){f();});pend=[];}
if(needYT()){var sc=document.createElement('script');sc.src='https://www.youtube.com/iframe_api';document.head.appendChild(sc);}

function destroy(li){var s=slots[li];if(!s)return;s.dead=true;
  try{if(s.v){s.v.pause();s.v.removeAttribute('src');s.v.load();}if(s.y&&s.y.destroy)s.y.destroy();}catch(e){}
  layers[li].innerHTML='';slots[li]=null;}

function prepare(li,ii,cb){
  destroy(li);var it=items[ii],s={ii:ii,ready:false};slots[li]=s;
  var done=function(err,why){if(s.ready||s.dead)return;s.err=!!err;s.ready=true;
    if(err)msg('Video '+(ii+1)+' no se puede reproducir: '+(why||'error al cargar'));if(cb)cb();};
  setTimeout(function(){done(false);},12000);
  if(it.t=='v'){
    var v=document.createElement('video');v.preload='auto';v.playsInline=true;v.src=it.src+(it.src.indexOf('__v/')>=0?'?n='+(++loadN):'');
    v.addEventListener('canplay',function(){done(false);});
    v.addEventListener('error',function(){var c=v.error?v.error.code:0;done(true,c==4?'formato no compatible o link no es un video':c==2?'error de red':'no se pudo abrir el link (código '+c+')');});
    layers[li].appendChild(v);s.v=v;v.load();
  }else{
    var d=document.createElement('div');layers[li].appendChild(d);
    var mk=function(){if(s.dead)return;s.y=new YT.Player(d,{width:'100%',height:'100%',videoId:it.id,
      playerVars:{autoplay:0,controls:0,rel:0,playsinline:1,iv_load_policy:3,disablekb:1,fs:0,origin:'https://farmex2026.github.io'},
      events:{onReady:function(e){e.target.mute();e.target.playVideo();},
        onStateChange:function(e){if(e.data==1&&!s.ready&&!s.live){e.target.pauseVideo();e.target.seekTo(0,true);done(false);}},
        onError:function(e){done(true,YTERR[e.data]||('código '+e.data));}}});};
    if(ytReady)mk();else pend.push(mk);
  }
}

function show(li){
  msg('');layers[li].style.zIndex=2;layers[li].style.opacity=1;
  var o=1-li;layers[o].style.zIndex=1;layers[o].style.opacity=0;
  cur=li;var nextIdx=(slots[li].ii+1)%items.length;
  setTimeout(function(){destroy(o);prepare(o,nextIdx);switching=false;},400);
}

function start(li){
  var s=slots[li];s.live=true;lastMove=Date.now();
  var shown=false,sh=function(){if(!shown&&!s.dead){shown=true;show(li);}};setTimeout(sh,4000);
  if(s.v){s.v.currentTime=0;s.v.addEventListener('playing',sh,{once:true});s.v.play();}
  else{s.y.unMute();s.y.seekTo(0,true);s.y.playVideo();
    var w=setInterval(function(){if(s.dead){clearInterval(w);return;}
      if(s.y.getPlayerState()==1&&s.y.getCurrentTime()>0.05){clearInterval(w);sh();}},40);}
}

function next(){
  var o=1-cur,n=slots[o];
  if(!n||!n.ready)return;
  if(n.err){var skip=(n.ii+1)%items.length;destroy(o);setTimeout(function(){prepare(o,skip);},3000);return;}
  switching=true;start(o);
}

setInterval(function(){
  if(switching)return;var s=slots[cur];if(!s||!s.live)return;
  var t=0,d=0,ended=false;
  try{if(s.v){t=s.v.currentTime;d=s.v.duration;ended=s.v.ended;}
      else{t=s.y.getCurrentTime();d=s.y.getDuration();ended=s.y.getPlayerState()===0;}}catch(e){}
  if(t!=lastT){lastT=t;lastMove=Date.now();}
  if(ended){if(!endedSince)endedSince=Date.now();else if(Date.now()-endedSince>3000){endedSince=0;restart();return;}}else endedSince=0;
  if(ended||(d>1&&d-t<0.35))next();
  if(Date.now()-lastMove>45000)restart();
},100);

function first(i){if(i>=items.length){setTimeout(function(){first(0);},20000);return;}
  msg('Cargando video '+(i+1)+'...');
  prepare(0,i,function(){var s=slots[0];if(s.err){setTimeout(function(){first(i+1);},4000);return;}start(0);});}
first(0);
</script></body></html>
""";
}
