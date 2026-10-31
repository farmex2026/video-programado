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
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;

public class MainActivity extends Activity {
    private static final int MAX = 5;
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
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        hideBars();
        if (urls().isEmpty()) showSettings();
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
        String first = l.get(0);
        if (l.size() == 1 && !isYoutube(first) && !isVideo(first)) {
            web.loadUrl(first); // una sola página web: se abre tal cual
            return;
        }
        List<String> media = new ArrayList<>();
        for (String u : l) if (isYoutube(u) || isVideo(u)) media.add(u);
        if (media.isEmpty()) { web.loadUrl(first); return; }
        String list = new JSONArray(media).toString().replace("</", "<\\/");
        web.loadDataWithBaseURL("https://farmex2026.github.io/",
                PLAYER.replace("__LIST__", list), "text/html", "utf-8", null);
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

    private boolean isYoutube(String u) {
        return u.contains("youtube.com") || u.contains("youtu.be");
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
                    for (int i = 0; i < MAX; i++) e.putString("url" + (i + 1), ets[i].getText().toString().trim());
                    e.putBoolean("sched", cb.isChecked()).putInt("start", st[0]).putInt("end", en[0]).apply();
                    stop();
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
var slots=[null,null],cur=0,switching=false,ytReady=false,pend=[],lastT=-1,lastMove=Date.now();

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
    var v=document.createElement('video');v.preload='auto';v.playsInline=true;v.src=it.src;
    v.addEventListener('canplay',function(){done(false);});
    v.addEventListener('error',function(){done(true,'no se pudo abrir el link');});
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
  if(ended||(d>1&&d-t<0.35))next();
  if(Date.now()-lastMove>45000)location.reload();
},100);

function first(i){if(i>=items.length){setTimeout(function(){location.reload();},20000);return;}
  msg('Cargando video '+(i+1)+'...');
  prepare(0,i,function(){var s=slots[0];if(s.err){setTimeout(function(){first(i+1);},4000);return;}start(0);});}
first(0);
</script></body></html>
""";
}
