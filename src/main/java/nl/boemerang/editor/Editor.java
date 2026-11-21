package nl.boemerang.editor;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;

public class Editor extends Activity {
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler handler = new Handler(Looper.getMainLooper());
    LinearLayout root; VideoView video; TextView range,status; SeekBar seek;
    LinearLayout strip; View selection; Button choose,play,save,share;
    ProgressBar progress;
    Uri source,saved; File ready; int start, duration;
    boolean busy, preview;
    final Runnable stopClip = new Runnable() {
        public void run() {
            if (preview || source==null || isDestroyed()) return;
            if(video.getCurrentPosition()>=start+5000){ video.pause(); video.seekTo(start); }
            handler.postDelayed(this,40);
        }
    };
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView label(String value,int size){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);root.addView(t);return t;
    }
    Button button(LinearLayout box,String title,Runnable action){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());box.addView(b);return b;
    }
    public void onCreate(Bundle state){
        super.onCreate(state);
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(16),dp(8),dp(16),dp(8));
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(16)+i.getSystemWindowInsetLeft(),dp(8)+i.getSystemWindowInsetTop(),dp(16)+i.getSystemWindowInsetRight(),dp(8)+i.getSystemWindowInsetBottom());return i.consumeSystemWindowInsets();});
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
        label("Boemerang",30).setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        choose=button(root,"Kies video",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE),1));
        FrameLayout stage=new FrameLayout(this);stage.setBackgroundColor(0xff182322);root.addView(stage,-1,dp(216));
        video=new VideoView(this);stage.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        video.setOnErrorListener((mp,a,b)->{status.setText("Deze video kan niet worden afgespeeld.");return true;});
        range=label("Kies een video van minimaal 5 seconden.",17);
        FrameLayout timeline=new FrameLayout(this);root.addView(timeline,-1,dp(44));
        strip=new LinearLayout(this);timeline.addView(strip,-1,-1);
        selection=new View(this){
            final Paint pen=new Paint();
            protected void onDraw(Canvas c){
                if(duration<5000 || source==null)return;
                float left=(float)start/duration*getWidth(), right=(float)(start+5000)/duration*getWidth();
                pen.setStyle(Paint.Style.FILL);pen.setColor(0x88000000);
                c.drawRect(0,0,left,getHeight(),pen);c.drawRect(right,0,getWidth(),getHeight(),pen);
                pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(dp(2));pen.setColor(0xff56e5b0);
                c.drawRect(left+1,1,Math.max(left+2,right-1),getHeight()-1,pen);
            }
        };timeline.addView(selection,-1,-1);seek=new SeekBar(this);root.addView(seek);
        seek.setContentDescription("Begin van het fragment");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int n,boolean user){if(user){invalidateMovie();start=n;updateRange();if(preview) showSource(false);else {video.pause();video.seekTo(start);}}}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){if(source!=null)video.seekTo(start);}
        });
        play=button(root,"Speel fragment af",()->showSource(true));
        button(root,"Bekijk boemerang",()->make(false)).setTag("preview");
        save=button(root,"Opslaan in galerij",()->make(true));
        share=button(root,"Deel filmpje",()->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,saved).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Deel filmpje")));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);root.addView(progress);progress.setMax(300);
        status=label("",15); controls();
        if(state!=null && state.getString("uri")!=null) load(Uri.parse(state.getString("uri")),state.getInt("start"));
    }
    void controls(){
        choose.setEnabled(!busy);boolean enabled=source!=null&&!busy;
        seek.setEnabled(enabled&&duration>5000);play.setEnabled(enabled);save.setEnabled(enabled);
        root.findViewWithTag("preview").setEnabled(enabled);
        share.setVisibility(saved==null?View.GONE:View.VISIBLE);progress.setVisibility(busy?View.VISIBLE:View.GONE);
    }
    void invalidateMovie(){
        if(ready!=null){if(preview)video.stopPlayback();ready.delete();ready=null;}
        saved=null;controls();
    }
    void updateRange(){selection.invalidate();range.setText(String.format(Locale.forLanguageTag("nl-NL"),"Fragment   %.2f – %.2f s",start/1000d,(start+5000)/1000d));}
    protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);if(req==1 && result==RESULT_OK && data!=null && data.getData()!=null)load(data.getData(),0);
    }
    void load(Uri uri,int initial){
        invalidateMovie();video.stopPlayback();preview=false;source=null;strip.removeAllViews();range.setText("");busy=true;controls();status.setText("Video openen…");
        worker.execute(()->{
            try(MediaMetadataRetriever r=new MediaMetadataRetriever()){
                r.setDataSource(this,uri);int length=Movie.number(r,MediaMetadataRetriever.METADATA_KEY_DURATION);
                if(length<5000)throw new IOException("Kies een video van minstens 5 seconden.");
                Bitmap[] thumbs=new Bitmap[7];
                for(int i=0;i<thumbs.length;i++) thumbs[i]=r.getScaledFrameAtTime((long)(length-1)*1000*i/(thumbs.length-1),MediaMetadataRetriever.OPTION_CLOSEST_SYNC,120,80);
                runOnUiThread(()->{if(isDestroyed())return;source=uri;duration=length;start=Math.max(0,Math.min(initial,length-5000));
                    for(Bitmap b:thumbs){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(b);strip.addView(image,new LinearLayout.LayoutParams(0,-1,1));}
                    seek.setMax(length-5000);seek.setProgress(start);busy=false;controls();updateRange();status.setText("Verschuif het beginpunt om 5 seconden te kiezen.");showSource(false);
                });
            }catch(Exception e){error(e);}
        });
    }
    void showSource(boolean auto){
        preview=false;handler.removeCallbacks(stopClip);video.setOnPreparedListener(mp->{mp.setVolume(0,0);video.seekTo(start);if(auto)video.start();handler.post(stopClip);});
        video.setVideoURI(source);
    }
    void showLoop(){
        preview=true;handler.removeCallbacks(stopClip);video.setOnPreparedListener(mp->{mp.setLooping(true);video.start();});video.setVideoPath(ready.getPath());
    }
    void make(boolean publish){
        if(busy || source==null)return;
        if(ready!=null&&!publish){showLoop();return;}
        if(saved!=null&&publish){status.setText("Dit fragment staat al in de galerij.");return;}
        busy=true;controls();video.pause();progress.setProgress(0);
        status.setText(ready==null?"Boemerang maken…":"Opslaan…");
        File previous=ready;Uri input=source;int offset=start;
        worker.execute(()->{
            File file=previous;
            try{
                if(file==null){
                    file=File.createTempFile("boemerang-", ".mp4",getCacheDir());
                    Movie.render(this,input,offset,file,n->{if(n%3==0)runOnUiThread(()->progress.setProgress(n));});
                }
                Uri result=publish?store(file):null;File complete=file;
                runOnUiThread(()->{if(isDestroyed()){complete.delete();return;}ready=complete;saved=result;busy=false;controls();showLoop();status.setText(publish?"Opgeslagen in Films/Boemerang.":"Voorbeeld · 10 seconden");});
            }catch(Exception e){if(previous==null&&file!=null)file.delete();error(e);}
        });
    }
    Uri store(File file)throws Exception{
        ContentResolver cr=getContentResolver();ContentValues values=new ContentValues();
        values.put("_display_name","Boemerang-"+System.currentTimeMillis()+".mp4");values.put("mime_type","video/mp4");values.put("relative_path","Movies/Boemerang");values.put("is_pending",1);
        Uri uri=cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("Opslaan is mislukt.");
        try{
            try(InputStream in=new FileInputStream(file);OutputStream out=cr.openOutputStream(uri)){
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
            values.clear();values.put("is_pending",0);if(cr.update(uri,values,null,null)!=1)throw new IOException("Publiceren is mislukt.");return uri;
        }catch(Exception e){cr.delete(uri,null,null);throw e;}
    }
    void error(Exception e){runOnUiThread(()->{if(isDestroyed())return;busy=false;controls();status.setText("Niet gelukt. "+(e.getMessage()==null?"Probeer een ander videobestand.":e.getMessage()));});}
    protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(source!=null){out.putString("uri",source.toString());out.putInt("start",start);}}
    protected void onDestroy(){handler.removeCallbacksAndMessages(null);worker.shutdownNow();video.stopPlayback();if(ready!=null)ready.delete();super.onDestroy();}
}
