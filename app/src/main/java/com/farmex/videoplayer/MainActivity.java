package com.farmex.videoplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences p;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean playing = false;

    private final Runnable tick = new Runnable() {
        @Override public void run() { check(); h.postDelayed(this, 20000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("cfg", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        hideBars();
        if (p.getString("url", "").isEmpty()) showSettings();
    }

    private void hideBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() {
        super.onResume();
        hideBars();
        playing = false;
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private void check() {
        String url = p.getString("url", "");
        if (url.isEmpty()) return;
        boolean on = inSchedule();
        if (on && !playing) play(url);
        else if (!on && playing) stop();
    }

    private boolean inSchedule() {
        if (!p.getBoolean("sched", false)) return true;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int a = p.getInt("start", 480), e = p.getInt("end", 1320);
        if (a == e) return true;
        if (a < e) return now >= a && now < e;
        return now >= a || now < e; // horario que cruza la medianoche
    }

    private void play(String url) {
        playing = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String u = fixDrive(url);
        String yt = youtubeHtml(u);
        if (yt != null) {
            web.loadDataWithBaseURL("https://farmex2026.github.io/", yt, "text/html", "utf-8", null);
        } else if (isVideo(u)) {
            String safe = u.replace("'", "%27");
            String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                    + "<body style='margin:0;background:#000;overflow:hidden'>"
                    + "<video src='" + safe + "' autoplay loop playsinline "
                    + "style='width:100vw;height:100vh;object-fit:contain'></video></body></html>";
            web.loadDataWithBaseURL(u, html, "text/html", "utf-8", null);
        } else {
            web.loadUrl(u);
        }
    }

    private void stop() {
        playing = false;
        web.loadUrl("about:blank");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private String fixDrive(String url) {
        Matcher m = Pattern.compile("drive\\.google\\.com/(?:file/d/|open\\?id=)([\\w-]+)").matcher(url);
        if (m.find()) return "https://drive.google.com/uc?export=download&id=" + m.group(1);
        return url;
    }

    // Arma un reproductor de YouTube que repite el video (o la lista) al terminar
    private String youtubeHtml(String u) {
        if (!u.contains("youtube.com") && !u.contains("youtu.be")) return null;
        Matcher mv = Pattern.compile("(?:youtu\\.be/|[?&]v=|/shorts/|/live/|/embed/)([\\w-]{11})").matcher(u);
        Matcher ml = Pattern.compile("[?&]list=([\\w-]+)").matcher(u);
        String id = mv.find() ? mv.group(1) : null;
        String list = ml.find() ? ml.group(1) : null;
        if (id == null && list == null) return null;

        String vars = "autoplay:1,controls:0,rel:0,playsinline:1,iv_load_policy:3,loop:1";
        String opts;
        if (list != null) {
            vars += ",listType:'playlist',list:'" + list + "'";
            opts = (id != null ? "videoId:'" + id + "'," : "");
        } else {
            vars += ",playlist:'" + id + "'";
            opts = "videoId:'" + id + "',";
        }
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "#p{position:absolute;top:0;left:0;width:100%;height:100%}</style></head><body>"
                + "<div id='p'></div>"
                + "<script src='https://www.youtube.com/iframe_api'></script><script>"
                + "function onYouTubeIframeAPIReady(){new YT.Player('p',{width:'100%',height:'100%',"
                + opts + "playerVars:{" + vars + "},events:{"
                + "onReady:function(e){e.target.playVideo();},"
                + "onStateChange:function(e){if(e.data===0){"
                + (list != null ? "e.target.playVideoAt(0);" : "e.target.seekTo(0);e.target.playVideo();")
                + "}},"
                + "onError:function(){setTimeout(function(){location.reload();},30000);}"
                + "}});}"
                + "setTimeout(function(){if(!window.YT||!YT.Player)location.reload();},60000);"
                + "</script></body></html>";
    }

    private boolean isVideo(String u) {
        String l = u.toLowerCase(Locale.US);
        return l.contains("drive.google.com/uc") || l.matches(".*\\.(mp4|webm|mkv|mov)(\\?.*)?$");
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

        TextView t = new TextView(this);
        t.setText("URL del video o página:");
        ll.addView(t);
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(p.getString("url", ""));
        ll.addView(et);

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

        new AlertDialog.Builder(this)
                .setTitle("Configuración")
                .setView(ll)
                .setPositiveButton("Guardar", (d, w) -> {
                    p.edit().putString("url", et.getText().toString().trim())
                            .putBoolean("sched", cb.isChecked())
                            .putInt("start", st[0]).putInt("end", en[0]).apply();
                    stop();
                    check();
                    hideBars();
                })
                .setNegativeButton("Cancelar", (d, w) -> hideBars())
                .setNeutralButton("Salir", (d, w) -> finish())
                .show();
    }
}
