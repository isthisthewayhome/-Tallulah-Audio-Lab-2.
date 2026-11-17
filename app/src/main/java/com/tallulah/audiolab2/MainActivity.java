package com.tallulah.audiolab2;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.AudioTrack;
import android.media.AudioFormat;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import android.media.MediaExtractor;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.io.File;
import java.io.RandomAccessFile;
import java.io.InputStream;
import java.io.OutputStream;
import android.content.ContentValues;
import android.provider.MediaStore;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.FileOutputStream;
import java.io.FileInputStream;

public class MainActivity extends Activity {
  private static final int PICK_AUDIO = 51;
  private final ArrayList<Track> tracks = new ArrayList<>();
  private LinearLayout trackList;
  private TextView status;
  private SeekBar masterSeek;
  private TextView masterTime;
  private boolean draggingMaster=false;
  private boolean playing = false;
  private final Handler clipHandler=new Handler(Looper.getMainLooper());
  private final Runnable clipMonitor=new Runnable(){public void run(){for(Track t:tracks){try{if(t.player.isPlaying()&&t.trimEndMs<t.player.getDuration()-150&&t.player.getCurrentPosition()>=t.trimEndMs){if(t.loop.isChecked()){t.player.seekTo(t.trimStartMs);}else{t.player.pause();t.player.seekTo(t.trimStartMs);}}}catch(Exception ignored){}}updateVolumes();updateMasterTimeline();updateTrackProgress();clipHandler.postDelayed(this,80);}};
  private final Handler rhythmHandler = new Handler(Looper.getMainLooper());
  private AudioTrack rhythmTone;
  private boolean rhythmEnabled=false;
  private int rhythmBpm=40, rhythmVolume=30, rhythmPunch=100, rhythmPattern=0, rhythmTick=0;
  private TextView rhythmStatus;
  private AudioTrack atmosphereTrack;
  private Thread atmosphereThread;
  private volatile boolean atmosphereOn=false, stereoOn=false;
  private volatile int atmosphereType=0, atmosphereVolume=20, stereoVolume=25, stereoBeat=6, stereoCarrier=180;
  private final Runnable rhythmTask=new Runnable(){@Override public void run(){if(!rhythmEnabled)return;try{if(rhythmTone!=null)playSoftPulse();}catch(Exception ignored){}rhythmTick++;int bpm=rhythmBpm;if(rhythmPattern==1)bpm=Math.max(15,Math.min(400,rhythmBpm+(int)(30*Math.sin(rhythmTick/5.0))));if(rhythmPattern==2)bpm=Math.min(400,rhythmBpm+rhythmTick/4);if(rhythmStatus!=null)rhythmStatus.setText("Rhythm guide • "+bpm+" BPM • audible cue");rhythmHandler.postDelayed(this,60000L/Math.max(1,bpm));}};
  private final int bg = Color.rgb(16,13,24), panel = Color.rgb(34,25,45), accent = Color.rgb(187,115,213);
  private static class Track {
    MediaPlayer player; Uri uri; SeekBar volume; SeekBar speed; SeekBar progress; TextView progressLabel; boolean draggingProgress=false; CheckBox loop; TextView label; LinearLayout card; boolean muted=false, solo=false; int trimStartMs=0,trimEndMs=0,fadeInMs=0,fadeOutMs=0;
    Track(MediaPlayer p,Uri u){player=p;uri=u;}
  }
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    clipHandler.post(clipMonitor);
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    ScrollView scroll = new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,30,24,24);scroll.addView(root);setContentView(scroll);
    TextView eyebrow=text("Y O U R   P R I V A T E   S O U N D   S T U D I O",13,true);eyebrow.setTextColor(accent);root.addView(eyebrow);
    TextView heading=text("Tallulah Audio Lab",30,true);heading.setTextColor(Color.WHITE);root.addView(heading);
    TextView sub=text("Layer audio and design your own soundscapes.",13,false);sub.setTextColor(0xffc6a6cf);root.addView(sub);
    TextView info=text("MASTER TRANSPORT",15,false);info.setPadding(0,22,0,18);root.addView(info);
    Button add=button("＋ Add audio");
    add.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,PICK_AUDIO);});
    LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);root.addView(controls);
    Button play=button("▶ Play mix");controls.addView(play,new LinearLayout.LayoutParams(0,-2,1));
    Button pause=button("Ⅱ Pause");controls.addView(pause,new LinearLayout.LayoutParams(0,-2,1));
    Button reset=button("↺ Restart edited mix");controls.addView(reset,new LinearLayout.LayoutParams(0,-2,1));
    play.setOnClickListener(v->{if(tracks.isEmpty()){status.setText("Add an audio file first.");return;}int count=0;for(Track t:tracks){try{if(!hasSolo()||t.solo){if(t.player.getCurrentPosition()>=t.trimEndMs)t.player.seekTo(t.trimStartMs);updateVolumes();t.player.start();count++;}}catch(Exception e){status.setText("Playback error: "+e.getMessage());}}if(count>0){playing=true;status.setText("Playing "+count+" track(s)");}});
    pause.setOnClickListener(v->{for(Track t:tracks)if(t.player.isPlaying())t.player.pause();playing=false;status.setText("Paused");});
    reset.setOnClickListener(v->{for(Track t:tracks){t.player.pause();t.player.seekTo(t.trimStartMs);}playing=false;status.setText("Reset to start");});
    TextView timelineTitle=text("Mix position",14,true);timelineTitle.setPadding(0,14,0,4);root.addView(timelineTitle);
    masterTime=text("0:00 / 0:00",14,false);root.addView(masterTime);
    masterSeek=new SeekBar(this);masterSeek.setMax(1);root.addView(masterSeek);
    masterSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int value,boolean user){if(user){masterTime.setText(clock(value)+" / "+clock(getMixDuration()));}}
      public void onStartTrackingTouch(SeekBar bar){draggingMaster=true;}
      public void onStopTrackingTouch(SeekBar bar){int target=bar.getProgress();for(Track t:tracks){try{t.player.seekTo(Math.min(t.trimEndMs,t.trimStartMs+target));}catch(Exception ignored){}}draggingMaster=false;updateMasterTimeline();}
    });
    Button saveProject=button("💾 Save Project (instant)");root.addView(saveProject);saveProject.setOnClickListener(v->saveProject());
    Button openProject=button("📂 My Projects");root.addView(openProject);openProject.setOnClickListener(v->showProjects());
    Button saveMix=button("⬇ Export WAV audio");root.addView(saveMix);
    saveMix.setOnClickListener(v->saveEditedAudio());
    status=text("Ready to import audio",14,false);status.setTextColor(0xffcbb5d5);status.setPadding(0,16,0,14);root.addView(status);
    TextView title=text("Audio layers",23,true);title.setPadding(0,24,0,8);root.addView(title);
    root.addView(add);
    Button video=button("▣ Extract audio from video");root.addView(video);video.setOnClickListener(v->status.setText("Video extraction is not available in this build yet."));
    trackList=new LinearLayout(this);trackList.setOrientation(1);root.addView(trackList);
    TextView beatTitle=text("Binaural beat generator",24,true);beatTitle.setPadding(0,28,0,8);root.addView(beatTitle);
    LinearLayout rhythmCard=new LinearLayout(this);rhythmCard.setOrientation(1);rhythmCard.setPadding(20,20,20,20);
    GradientDrawable rhythmBg=new GradientDrawable();rhythmBg.setColor(0xff1e1e32);rhythmBg.setCornerRadius(28);rhythmCard.setBackground(rhythmBg);root.addView(rhythmCard);
    TextView rhythmHeading=text("HISMITH STROKE RHYTHM",18,true);rhythmHeading.setTextColor(0xffb497ff);rhythmCard.addView(rhythmHeading);
    TextView rhythmDesc=text("Audible timing guide only. Does not connect to or control a device.",13,false);rhythmCard.addView(rhythmDesc);
    rhythmStatus=text("Rhythm guide stopped",14,false);rhythmStatus.setPadding(0,14,0,8);rhythmCard.addView(rhythmStatus);
    Button rhythmToggle=button("▶ Start rhythm guide");rhythmCard.addView(rhythmToggle);
    rhythmToggle.setOnClickListener(v->{rhythmEnabled=!rhythmEnabled;rhythmHandler.removeCallbacks(rhythmTask);if(rhythmEnabled){rhythmTick=0;resetRhythmTone();rhythmHandler.post(rhythmTask);rhythmToggle.setText("■ Stop rhythm guide");}else{rhythmToggle.setText("▶ Start rhythm guide");rhythmStatus.setText("Rhythm guide stopped");}});
    TextView tempo=text("Live BPM • 40",16,true);rhythmCard.addView(tempo);
    SeekBar tempoSlider=new SeekBar(this);tempoSlider.setMax(385);tempoSlider.setProgress(25);rhythmCard.addView(tempoSlider);
    tempoSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){rhythmBpm=p+15;tempo.setText("Live BPM • "+rhythmBpm);if(rhythmEnabled){rhythmHandler.removeCallbacks(rhythmTask);rhythmHandler.post(rhythmTask);}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    TextView loud=text("Guide volume • 30%",14,false);rhythmCard.addView(loud);
    SeekBar loudSlider=new SeekBar(this);loudSlider.setMax(100);loudSlider.setProgress(30);rhythmCard.addView(loudSlider);
    loudSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){rhythmVolume=p;loud.setText("Guide volume • "+p+"%");if(rhythmEnabled)resetRhythmTone();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    TextView punchLabel=text("Bass Punch • 100%",14,false);rhythmCard.addView(punchLabel);
    SeekBar punchSlider=new SeekBar(this);punchSlider.setMax(200);punchSlider.setProgress(100);rhythmCard.addView(punchSlider);
    punchSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){rhythmPunch=p;punchLabel.setText("Bass Punch • "+p+"%");if(rhythmEnabled)resetRhythmTone();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    Spinner patterns=new Spinner(this);String[] names={"Steady pulse","Wave • rise and fall","Build • gradual acceleration"};ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);patterns.setAdapter(adapter);rhythmCard.addView(patterns);
    patterns.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){rhythmPattern=position;rhythmTick=0;}public void onNothingSelected(android.widget.AdapterView<?> parent){}});

    TextView binauralTitle=text("Stereo binaural beat generator",23,true);binauralTitle.setPadding(0,26,0,10);root.addView(binauralTitle);
    LinearLayout binCard=new LinearLayout(this);binCard.setOrientation(1);binCard.setPadding(20,20,20,20);
    GradientDrawable binShape=new GradientDrawable();binShape.setColor(0xff1e1e32);binShape.setCornerRadius(24);binCard.setBackground(binShape);root.addView(binCard);
    CheckBox stereoSwitch=new CheckBox(this);stereoSwitch.setText("Enable stereo tones");stereoSwitch.setTextColor(Color.WHITE);binCard.addView(stereoSwitch);
    stereoSwitch.setOnCheckedChangeListener((stereoButton,on)->{stereoOn=on;startAtmosphereIfNeeded();});
    String[] binauralPresetNames={"Deep Rest • 2 Hz","Dreamy • 6 Hz","Calm • 10 Hz","Focused • 18 Hz","Energized • 36 Hz","Intimate • 7 Hz","Custom"};
    Spinner vibe=new Spinner(this);vibe.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,binauralPresetNames));binCard.addView(vibe);
    TextView beatLabel=text("Beat difference • 6 Hz",14,false);binCard.addView(beatLabel);
    SeekBar beatSlider=new SeekBar(this);beatSlider.setMax(39);beatSlider.setProgress(5);binCard.addView(beatSlider);
    beatSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){stereoBeat=p+1;beatLabel.setText("Beat difference • "+stereoBeat+" Hz");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    vibe.setSelection(1);vibe.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){int[] presets={2,6,10,18,36,7};if(pos<6)beatSlider.setProgress(presets[pos]-1);}public void onNothingSelected(android.widget.AdapterView<?> p){}});
    TextView carrierLabel=text("Carrier • 180 Hz",14,false);binCard.addView(carrierLabel);
    SeekBar carrierSlider=new SeekBar(this);carrierSlider.setMax(350);carrierSlider.setProgress(100);binCard.addView(carrierSlider);
    carrierSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){stereoCarrier=p+80;carrierLabel.setText("Carrier • "+stereoCarrier+" Hz");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    TextView stereoVol=text("Tone volume • 25%",14,false);binCard.addView(stereoVol);
    SeekBar stereoSlider=new SeekBar(this);stereoSlider.setMax(100);stereoSlider.setProgress(25);binCard.addView(stereoSlider);
    stereoSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){stereoVolume=p;stereoVol.setText("Tone volume • "+p+"%");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    TextView dreamTitle=text("Dream Sounds",23,true);dreamTitle.setPadding(0,26,0,10);root.addView(dreamTitle);
    LinearLayout dreamCard=new LinearLayout(this);dreamCard.setOrientation(1);dreamCard.setPadding(20,20,20,20);
    GradientDrawable dreamShape=new GradientDrawable();dreamShape.setColor(0xff1e1e32);dreamShape.setCornerRadius(24);dreamCard.setBackground(dreamShape);root.addView(dreamCard);
    Spinner dreamPicker=new Spinner(this);dreamPicker.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Dark Drone","Ocean Drift","Airy Whisper","Soft Night Pulse"}));dreamCard.addView(dreamPicker);
    dreamPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){atmosphereType=pos;}public void onNothingSelected(android.widget.AdapterView<?> p){}});
    TextView dreamVol=text("Dream volume • 20%",14,false);dreamCard.addView(dreamVol);
    SeekBar dreamSlider=new SeekBar(this);dreamSlider.setMax(100);dreamSlider.setProgress(20);dreamCard.addView(dreamSlider);
    dreamSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){atmosphereVolume=p;dreamVol.setText("Dream volume • "+p+"%");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    Button dreamButton=button("▶ Start Dream Sounds");dreamCard.addView(dreamButton);
    dreamButton.setOnClickListener(v->{atmosphereOn=!atmosphereOn;startAtmosphereIfNeeded();dreamButton.setText(atmosphereOn?"■ Stop Dream Sounds":"▶ Start Dream Sounds");});
    dreamCard.addView(text("Synthesized sound textures. Use stereo headphones for binaural tones.",12,false));
    TextView foot=text("Clip editing, export, and Govee lighting are still being rebuilt.",13,false);foot.setTextColor(0xffb7a5be);foot.setPadding(0,28,0,0);root.addView(foot);
  }
  private TextView text(String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
  private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTextSize(13);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff5b346c));return b;}
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request!=PICK_AUDIO||result!=RESULT_OK||data==null)return;
    if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)load(data.getClipData().getItemAt(i).getUri());}
    else if(data.getData()!=null)load(data.getData());
  }
  private void load(Uri uri){
    try{
      getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }catch(Exception ignored){}
    try{
      MediaPlayer player=new MediaPlayer();player.setDataSource(this,uri);player.prepare();
      Track t=new Track(player,uri);t.trimEndMs=player.getDuration();tracks.add(t);
      LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(16,16,16,16);
      GradientDrawable shape=new GradientDrawable();shape.setColor(panel);shape.setCornerRadius(22);card.setBackground(shape);
      LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,12,0,0);trackList.addView(card,cp);t.card=card;
      String name=uri.getLastPathSegment();if(name==null)name="Audio track "+tracks.size();
      t.label=text(name,15,true);t.label.setSingleLine(true);t.label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);card.addView(t.label);
      TextView duration=text("Duration: "+(player.getDuration()/60000)+":"+String.format(java.util.Locale.US,"%02d",(player.getDuration()/1000)%60),12,false);card.addView(duration);
      TextView vlabel=text("Layer volume 100%",13,false);card.addView(vlabel);
      SeekBar vol=new SeekBar(this);vol.setMax(100);vol.setProgress(100);card.addView(vol);t.volume=vol;
      vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){float level=p/100f;updateVolumes();vlabel.setText("Layer volume "+p+"%");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
      LinearLayout row=new LinearLayout(this);row.setOrientation(0);card.addView(row);
      CheckBox loop=new CheckBox(this);loop.setText("Loop");loop.setTextColor(Color.WHITE);row.addView(loop);t.loop=loop;loop.setOnCheckedChangeListener((b,on)->{boolean whole=t.trimStartMs==0 && t.trimEndMs>=player.getDuration()-150;player.setLooping(on&&whole);});
      player.setOnCompletionListener(mp->{if(t.loop!=null&&t.loop.isChecked()){try{mp.seekTo(t.trimStartMs);mp.start();}catch(Exception e){status.setText("Loop error: "+e.getMessage());}}});
      Button solo=button("▶ Track");row.addView(solo);solo.setOnClickListener(v->{if(player.isPlaying()){player.pause();solo.setText("▶ Track");}else{updateVolumes();player.start();solo.setText("Ⅱ Track");}});
      Button mute=button("Mute");row.addView(mute);mute.setOnClickListener(v->{t.muted=!t.muted;mute.setText(t.muted?"Unmute":"Mute");updateVolumes();});
      Button isolate=button("Solo");row.addView(isolate);isolate.setOnClickListener(v->{t.solo=!t.solo;isolate.setText(t.solo?"Unsolo":"Solo");updateVolumes();});
      TextView seekTitle=text("Track progress • 0:00 / "+clock(t.trimEndMs-t.trimStartMs),13,false);card.addView(seekTitle);t.progressLabel=seekTitle;
      SeekBar seek=new SeekBar(this);seek.setMax(Math.max(1,t.trimEndMs-t.trimStartMs));seek.setProgress(0);card.addView(seek);t.progress=seek;
      seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
        public void onProgressChanged(SeekBar b,int p,boolean user){if(user){int length=Math.max(0,t.trimEndMs-t.trimStartMs);t.progressLabel.setText("Track progress • "+clock(Math.min(p,length))+" / "+clock(length));}}
        public void onStartTrackingTouch(SeekBar b){t.draggingProgress=true;}
        public void onStopTrackingTouch(SeekBar b){int length=Math.max(0,t.trimEndMs-t.trimStartMs);try{player.seekTo(t.trimStartMs+Math.min(b.getProgress(),length));}catch(Exception ignored){}t.draggingProgress=false;updateTrackProgress();}
      });
      Button clipEdit=button("✂ Trim clip");card.addView(clipEdit);clipEdit.setOnClickListener(v->showTrimEditor(t));
      Button fades=button("◐ Fade in / Fade out");card.addView(fades);fades.setOnClickListener(v->showFadeEditor(t));
      TextView speedLabel=text("Speed 1.00×",13,false);card.addView(speedLabel);
      SeekBar speed=new SeekBar(this);t.speed=speed;speed.setMax(150);speed.setProgress(50);card.addView(speed);
      speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){float rate=0.5f+p/100f;speedLabel.setText(String.format(java.util.Locale.US,"Speed %.2f×",rate));try{player.setPlaybackParams(player.getPlaybackParams().setSpeed(rate));}catch(Exception ignored){}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
      Button remove=button("✕");row.addView(remove);remove.setOnClickListener(v->{player.release();tracks.remove(t);trackList.removeView(card);status.setText("Track removed");});
      status.setText("Imported "+tracks.size()+" track(s)");
    }catch(Exception e){status.setText("Could not open audio: "+e.getMessage());}
  }
  private void showFadeEditor(Track t){
    int duration=Math.max(1,t.trimEndMs-t.trimStartMs);
    LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(28,16,28,12);
    TextView info=text("Clip duration: "+clock(duration)+" • fades change playback volume, not your original file.",13,false);layout.addView(info);
    TextView inLabel=text("",15,true);layout.addView(inLabel);
    SeekBar fadeIn=new SeekBar(this);fadeIn.setMax(duration/1000);fadeIn.setProgress(t.fadeInMs/1000);layout.addView(fadeIn);
    TextView outLabel=text("",15,true);layout.addView(outLabel);
    SeekBar fadeOut=new SeekBar(this);fadeOut.setMax(duration/1000);fadeOut.setProgress(t.fadeOutMs/1000);layout.addView(fadeOut);
    Runnable refresh=()->{
      if(fadeIn.getProgress()+fadeOut.getProgress()>duration/1000){
        int allowed=Math.max(0,duration/1000-fadeIn.getProgress());fadeOut.setProgress(allowed);
      }
      inLabel.setText("Fade in: "+fadeIn.getProgress()+" seconds");
      outLabel.setText("Fade out: "+fadeOut.getProgress()+" seconds");
    };
    SeekBar.OnSeekBarChangeListener change=new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar b,int p,boolean fromUser){refresh.run();}
      public void onStartTrackingTouch(SeekBar b){}
      public void onStopTrackingTouch(SeekBar b){}
    };
    fadeIn.setOnSeekBarChangeListener(change);fadeOut.setOnSeekBarChangeListener(change);refresh.run();
    new android.app.AlertDialog.Builder(this).setTitle("Playback fades").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{
      t.fadeInMs=fadeIn.getProgress()*1000;t.fadeOutMs=fadeOut.getProgress()*1000;
      updateVolumes();status.setText("Fades active: in "+(t.fadeInMs/1000)+"s • out "+(t.fadeOutMs/1000)+"s. Restart track to hear fade-in.");
    }).show();
  }
  private int getMixDuration(){int max=0;for(Track t:tracks)max=Math.max(max,t.trimEndMs-t.trimStartMs);return max;}
  private void updateMasterTimeline(){
    if(masterSeek==null||masterTime==null||draggingMaster)return;
    int duration=getMixDuration(),position=0;
    for(Track t:tracks){try{position=Math.max(position,Math.max(0,t.player.getCurrentPosition()-t.trimStartMs));}catch(Exception ignored){}}
    masterSeek.setMax(Math.max(1,duration));masterSeek.setProgress(Math.min(position,duration));
    masterTime.setText(clock(position)+" / "+clock(duration));
  }
  private void updateTrackProgress(){
    for(Track t:tracks){
      if(t.progress==null||t.progressLabel==null||t.draggingProgress)continue;
      try{
        int length=Math.max(0,t.trimEndMs-t.trimStartMs);
        int position=Math.max(0,Math.min(length,t.player.getCurrentPosition()-t.trimStartMs));
        t.progress.setMax(Math.max(1,length));t.progress.setProgress(position);
        t.progressLabel.setText("Track progress • "+clock(position)+" / "+clock(length)+(t.player.isPlaying()?" • Playing":""));
      }catch(Exception ignored){}
    }
  }
  private static class PcmExport {
    File file;int rate,channels;long frames;
  }
  private PcmExport decodeForMix(Uri source,int startMs,int endMs,int fadeInMs,int fadeOutMs,int volume)throws Exception{
    MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;RandomAccessFile raw=null;File file=null;
    try{
      ex.setDataSource(this,source,null);int index=-1;MediaFormat fmt=null;
      for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){index=i;fmt=f;break;}}
      if(index<0)throw new Exception("Audio stream missing");
      ex.selectTrack(index);codec=MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));codec.configure(fmt,null,null,0);codec.start();
      ex.seekTo(startMs*1000L,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      file=File.createTempFile("tallulah_pcm_",".raw",getCacheDir());raw=new RandomAccessFile(file,"rw");
      int rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      long frames=0;boolean inDone=false,outDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
      while(!outDone){
        if(!inDone){int idx=codec.dequeueInputBuffer(10000);if(idx>=0){ByteBuffer input=codec.getInputBuffer(idx);input.clear();int n=ex.readSampleData(input,0);long pts=ex.getSampleTime();if(n<0||pts>=endMs*1000L){codec.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true;}else{codec.queueInputBuffer(idx,0,n,Math.max(0,pts),0);ex.advance();}}}
        int idx=codec.dequeueOutputBuffer(info,10000);
        if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
          MediaFormat out=codec.getOutputFormat();rate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          if(out.containsKey(MediaFormat.KEY_PCM_ENCODING)&&out.getInteger(MediaFormat.KEY_PCM_ENCODING)!=2)throw new Exception("Only 16-bit decoded audio is supported");
        }else if(idx>=0){
          if(info.size>0){
            ByteBuffer buf=codec.getOutputBuffer(idx);buf.position(info.offset);buf.limit(info.offset+info.size);
            int frameSize=channels*2;byte[] data=new byte[info.size];buf.get(data);
            for(int j=0;j+frameSize<=data.length;j+=frameSize){
              long time=info.presentationTimeUs+(long)(j/frameSize)*1000000L/rate;
              if(time<startMs*1000L||time>=endMs*1000L)continue;
              float gain=volume/100f;
              if(fadeInMs>0)gain*=Math.max(0f,Math.min(1f,(time-startMs*1000L)/(fadeInMs*1000f)));
              if(fadeOutMs>0)gain*=Math.max(0f,Math.min(1f,(endMs*1000L-time)/(fadeOutMs*1000f)));
              for(int c=0;c<channels;c++){int p=j+c*2;short sample=(short)((data[p]&255)|(data[p+1]<<8));int value=Math.max(-32768,Math.min(32767,Math.round(sample*gain)));raw.write(value&255);raw.write((value>>8)&255);}
              frames++;
            }
          }
          if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outDone=true;
          codec.releaseOutputBuffer(idx,false);
        }
      }
      if(frames==0)throw new Exception("One track decoded no audio");
      PcmExport result=new PcmExport();result.file=file;result.rate=rate;result.channels=channels;result.frames=frames;return result;
    }catch(Exception e){if(file!=null)file.delete();throw e;}
    finally{if(raw!=null)try{raw.close();}catch(Exception ignored){}if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}ex.release();}
  }
  private PcmExport normalizeForMix(PcmExport input)throws Exception{
    if(input.rate==48000&&input.channels==2)return input;
    if(input.rate<=0||(input.channels!=1&&input.channels!=2))throw new Exception("Only mono or stereo tracks are supported");
    File normalized=File.createTempFile("tallulah_normalized_",".raw",getCacheDir());
    long frames=(input.frames*48000L+input.rate-1)/input.rate;
    try(RandomAccessFile in=new RandomAccessFile(input.file,"r");RandomAccessFile out=new RandomAccessFile(normalized,"rw")){
      long cached=-1;int[] previous=new int[2],following=new int[2];
      for(long i=0;i<frames;i++){
        double source=i*(input.rate/48000.0);
        long frame=Math.min(input.frames-1,(long)source);
        double fraction=source-frame;
        if(frame!=cached){
          in.seek(frame*input.channels*2L);
          for(int c=0;c<input.channels;c++){int lo=in.readUnsignedByte(),hi=in.readUnsignedByte();previous[c]=(short)(lo|(hi<<8));}
          if(frame+1<input.frames){for(int c=0;c<input.channels;c++){int lo=in.readUnsignedByte(),hi=in.readUnsignedByte();following[c]=(short)(lo|(hi<<8));}}
          else System.arraycopy(previous,0,following,0,input.channels);
          cached=frame;
        }
        for(int c=0;c<2;c++){
          int channel=input.channels==1?0:c;
          int value=(int)Math.round(previous[channel]*(1-fraction)+following[channel]*fraction);
          value=Math.max(-32768,Math.min(32767,value));
          out.write(value&255);out.write((value>>8)&255);
        }
      }
    }catch(Exception e){normalized.delete();throw e;}
    PcmExport result=new PcmExport();result.file=normalized;result.rate=48000;result.channels=2;result.frames=frames;return result;
  }
  private void saveTwoTrackMix(){
    Track first=tracks.get(0),second=tracks.get(1);
    if(first.loop.isChecked()||second.loop.isChecked()){status.setText("Turn off Loop on both tracks before exporting this test mix.");return;}
    final Uri[] sources={first.uri,second.uri};
    final int[][] settings={{first.trimStartMs,first.trimEndMs,first.fadeInMs,first.fadeOutMs,first.volume.getProgress()},{second.trimStartMs,second.trimEndMs,second.fadeInMs,second.fadeOutMs,second.volume.getProgress()}};
    status.setText("Rendering two-track mix…");
    new Thread(()->{
      PcmExport a=null,b=null,originalA=null,originalB=null;File wavFile=null;Uri destination=null;
      try{
        a=decodeForMix(sources[0],settings[0][0],settings[0][1],settings[0][2],settings[0][3],settings[0][4]);
        b=decodeForMix(sources[1],settings[1][0],settings[1][1],settings[1][2],settings[1][3],settings[1][4]);
        originalA=a;originalB=b;
        a=normalizeForMix(originalA);b=normalizeForMix(originalB);
        long frames=Math.max(a.frames,b.frames),bytes=frames*a.channels*2L;
        if(bytes>0xffffffffL-36)throw new Exception("Mix exceeds WAV size limit");
        wavFile=File.createTempFile("tallulah_mix_",".wav",getCacheDir());
        try(RandomAccessFile wav=new RandomAccessFile(wavFile,"rw");RandomAccessFile ra=new RandomAccessFile(a.file,"r");RandomAccessFile rb=new RandomAccessFile(b.file,"r")){
          wav.writeBytes("RIFF");writeLe(wav,(int)(36+bytes));wav.writeBytes("WAVEfmt ");writeLe(wav,16);writeShortLe(wav,1);writeShortLe(wav,a.channels);writeLe(wav,a.rate);writeLe(wav,a.rate*a.channels*2);writeShortLe(wav,a.channels*2);writeShortLe(wav,16);wav.writeBytes("data");writeLe(wav,(int)bytes);
          int block=4096;byte[] left=new byte[block*a.channels*2],right=new byte[left.length],mixed=new byte[left.length];
          for(long offset=0;offset<frames;offset+=block){
            int n=(int)Math.min(block,frames-offset),size=n*a.channels*2;
            java.util.Arrays.fill(left,(byte)0);java.util.Arrays.fill(right,(byte)0);
            int l=(int)Math.min(size,Math.max(0,(a.frames-offset)*a.channels*2));if(l>0)ra.readFully(left,0,l);
            int r=(int)Math.min(size,Math.max(0,(b.frames-offset)*b.channels*2));if(r>0)rb.readFully(right,0,r);
            for(int i=0;i<size;i+=2){short x=(short)((left[i]&255)|(left[i+1]<<8)),y=(short)((right[i]&255)|(right[i+1]<<8));int sum=Math.max(-32768,Math.min(32767,x+y));mixed[i]=(byte)sum;mixed[i+1]=(byte)(sum>>8);}
            wav.write(mixed,0,size);
          }
        }
        ContentValues values=new ContentValues();values.put(MediaStore.Audio.Media.DISPLAY_NAME,"Tallulah_TwoTrack_"+System.currentTimeMillis()+".wav");values.put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav");values.put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/Tallulah Audio Lab");values.put(MediaStore.Audio.Media.IS_PENDING,1);
        destination=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);if(destination==null)throw new Exception("Unable to create saved file");
        try(InputStream in=new java.io.FileInputStream(wavFile);OutputStream out=getContentResolver().openOutputStream(destination)){if(out==null)throw new Exception("Unable to write saved file");byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
        values.clear();values.put(MediaStore.Audio.Media.IS_PENDING,0);getContentResolver().update(destination,values,null,null);
        runOnUiThread(()->status.setText("Two-track WAV saved to Music/Tallulah Audio Lab"));
      }catch(Exception e){if(destination!=null)getContentResolver().delete(destination,null,null);String message=e.getMessage();runOnUiThread(()->status.setText("Mix export failed: "+message));}
      finally{if(a!=null)a.file.delete();if(b!=null)b.file.delete();if(originalA!=null)originalA.file.delete();if(originalB!=null)originalB.file.delete();if(wavFile!=null)wavFile.delete();}
    },"TwoTrackExport").start();
  }
  private File projectRoot(){File dir=new File(getFilesDir(),"projects");dir.mkdirs();return dir;}
  private void saveProject(){
    if(tracks.isEmpty()){status.setText("Add audio before saving a project");return;}
    EditText input=new EditText(this);input.setSingleLine(true);input.setText("My Mix");
    new android.app.AlertDialog.Builder(this).setTitle("Save project").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",(dialog,which)->{
      String name=input.getText().toString().trim();if(name.isEmpty())name="My Mix";
      final String projectName=name;
      final ArrayList<JSONObject> snapshots=new ArrayList<>();
      try{for(Track t:tracks){
        JSONObject item=new JSONObject();item.put("uri",t.uri.toString());item.put("volume",t.volume.getProgress());item.put("loop",t.loop.isChecked());item.put("speed",t.speed.getProgress());item.put("start",t.trimStartMs);item.put("end",t.trimEndMs);item.put("fadeIn",t.fadeInMs);item.put("fadeOut",t.fadeOutMs);item.put("mute",t.muted);item.put("solo",t.solo);snapshots.add(item);
      }}catch(Exception e){status.setText("Cannot prepare project: "+e.getMessage());return;}
      status.setText("Saving project audio…");
      new Thread(()->{
        File folder=new File(projectRoot(),"project_"+System.currentTimeMillis());
        try{
          if(!folder.mkdirs())throw new Exception("Cannot create project folder");
          JSONArray saved=new JSONArray();
          for(int i=0;i<snapshots.size();i++){
            JSONObject item=snapshots.get(i);File audio=new File(folder,"track_"+i+".audio");
            try(InputStream in=getContentResolver().openInputStream(Uri.parse(item.getString("uri")));OutputStream out=new FileOutputStream(audio)){
              if(in==null)throw new Exception("Missing source audio");byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
            item.remove("uri");item.put("file",audio.getName());saved.put(item);
          }
          JSONObject project=new JSONObject();project.put("version",1);project.put("name",projectName);project.put("tracks",saved);
          try(OutputStream out=new FileOutputStream(new File(folder,"project.json"))){out.write(project.toString().getBytes("UTF-8"));}
          runOnUiThread(()->status.setText("Project saved: "+projectName+" • "+snapshots.size()+" tracks"));
        }catch(Exception e){deleteProjectFolder(folder);String message=e.getMessage();runOnUiThread(()->status.setText("Project save failed: "+message));}
      },"SaveProject").start();
    }).show();
  }
  private void deleteProjectFolder(File folder){File[] files=folder.listFiles();if(files!=null)for(File file:files)file.delete();folder.delete();}
  private void showProjects(){
    File[] all=projectRoot().listFiles(file->file.isDirectory()&&new File(file,"project.json").exists());
    if(all==null||all.length==0){status.setText("No saved projects yet");return;}
    java.util.Arrays.sort(all,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
    String[] labels=new String[all.length];
    for(int i=0;i<all.length;i++){try(InputStream in=new FileInputStream(new File(all[i],"project.json"))){byte[] bytes=new byte[(int)Math.min(65536,new File(all[i],"project.json").length())];int n=in.read(bytes);labels[i]=new JSONObject(new String(bytes,0,Math.max(0,n),"UTF-8")).optString("name","Project");}catch(Exception e){labels[i]="Project "+(i+1);}}
    new android.app.AlertDialog.Builder(this).setTitle("My Projects").setItems(labels,(dialog,index)->openProject(all[index])).setNegativeButton("Cancel",null).show();
  }
  private void openProject(File folder){
    try{
      File jsonFile=new File(folder,"project.json");byte[] bytes=new byte[(int)jsonFile.length()];
      try(InputStream in=new FileInputStream(jsonFile)){int offset=0,n;while(offset<bytes.length&&(n=in.read(bytes,offset,bytes.length-offset))>0)offset+=n;}
      JSONObject project=new JSONObject(new String(bytes,"UTF-8"));JSONArray items=project.getJSONArray("tracks");
      for(int i=0;i<items.length();i++){File audio=new File(folder,items.getJSONObject(i).getString("file"));if(!audio.exists())throw new Exception("Missing saved track "+(i+1));}
      for(Track t:tracks){try{t.player.release();}catch(Exception ignored){}}
      tracks.clear();trackList.removeAllViews();
      for(int i=0;i<items.length();i++){
        JSONObject item=items.getJSONObject(i);load(Uri.fromFile(new File(folder,item.getString("file"))));
        Track t=tracks.get(tracks.size()-1);
        t.volume.setProgress(item.optInt("volume",100));t.speed.setProgress(item.optInt("speed",50));
        t.trimStartMs=item.optInt("start",0);t.trimEndMs=Math.min(t.player.getDuration(),item.optInt("end",t.player.getDuration()));
        t.fadeInMs=item.optInt("fadeIn",0);t.fadeOutMs=item.optInt("fadeOut",0);
        t.muted=item.optBoolean("mute",false);t.solo=item.optBoolean("solo",false);
        t.loop.setChecked(item.optBoolean("loop",false));t.player.seekTo(t.trimStartMs);
      }
      updateVolumes();status.setText("Opened project: "+project.optString("name","My Mix"));
    }catch(Exception e){status.setText("Could not open project: "+e.getMessage());}
  }
  private void saveEditedAudio(){
    if(tracks.isEmpty()){status.setText("Add audio before saving.");return;}
    if(tracks.size()==2){saveTwoTrackMix();return;}
    if(tracks.size()!=1){status.setText("This test build supports one or two tracks.");return;}
    Track t=tracks.get(0);
    status.setText("Rendering edited audio…");
    final Uri source=t.uri;
    final int start=t.trimStartMs,end=t.trimEndMs,fadeIn=t.fadeInMs,fadeOut=t.fadeOutMs,volume=t.volume.getProgress();
    new Thread(()->{
      MediaExtractor extractor=null;MediaCodec decoder=null;RandomAccessFile wav=null;File temp=null;Uri dest=null;
      try{
        extractor=new MediaExtractor();extractor.setDataSource(this,source,null);
        int track=-1;MediaFormat format=null;
        for(int i=0;i<extractor.getTrackCount();i++){MediaFormat candidate=extractor.getTrackFormat(i);String mime=candidate.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;format=candidate;break;}}
        if(track<0)throw new Exception("No audio stream found");
        extractor.selectTrack(track);
        decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));decoder.configure(format,null,null,0);decoder.start();
        extractor.seekTo(start*1000L,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        temp=File.createTempFile("tallulah_export_",".wav",getCacheDir());
        wav=new RandomAccessFile(temp,"rw");wav.write(new byte[44]);
        int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        long written=0;boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(!outputDone){
          if(!inputDone){int idx=decoder.dequeueInputBuffer(10000);if(idx>=0){ByteBuffer input=decoder.getInputBuffer(idx);input.clear();int size=extractor.readSampleData(input,0);long time=extractor.getSampleTime();if(size<0||time>=end*1000L){decoder.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{decoder.queueInputBuffer(idx,0,size,Math.max(0,time),0);extractor.advance();}}}
          int idx=decoder.dequeueOutputBuffer(info,10000);
          if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
            MediaFormat actual=decoder.getOutputFormat();rate=actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if(actual.containsKey(MediaFormat.KEY_PCM_ENCODING)&&actual.getInteger(MediaFormat.KEY_PCM_ENCODING)!=2)throw new Exception("This decoder does not output 16-bit PCM");
          }else if(idx>=0){
            if(info.size>0&&info.presentationTimeUs>=start*1000L&&info.presentationTimeUs<end*1000L){
              ByteBuffer pcm=decoder.getOutputBuffer(idx);pcm.position(info.offset);pcm.limit(info.offset+info.size);
              byte[] data=new byte[info.size];pcm.get(data);
              int usable=data.length-(data.length%2);
              for(int j=0;j<usable;j+=2){
                long sampleTime=info.presentationTimeUs+(long)(j/(2*channels))*1000000L/rate;
                float gain=volume/100f;
                long elapsed=sampleTime-start*1000L,remaining=end*1000L-sampleTime;
                if(fadeIn>0)gain*=Math.max(0f,Math.min(1f,elapsed/(fadeIn*1000f)));
                if(fadeOut>0)gain*=Math.max(0f,Math.min(1f,remaining/(fadeOut*1000f)));
                short sample=(short)((data[j]&255)|(data[j+1]<<8));
                int adjusted=Math.max(-32768,Math.min(32767,Math.round(sample*gain)));
                data[j]=(byte)adjusted;data[j+1]=(byte)(adjusted>>8);
              }
              wav.write(data,0,usable);written+=usable;
            }
            if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outputDone=true;
            decoder.releaseOutputBuffer(idx,false);
          }
        }
        if(written==0||written>0xffffffffL-36)throw new Exception("No audio produced or clip too long");
        wav.seek(0);wav.writeBytes("RIFF");writeLe(wav,(int)(36+written));wav.writeBytes("WAVEfmt ");writeLe(wav,16);writeShortLe(wav,1);writeShortLe(wav,channels);writeLe(wav,rate);writeLe(wav,rate*channels*2);writeShortLe(wav,channels*2);writeShortLe(wav,16);wav.writeBytes("data");writeLe(wav,(int)written);wav.close();wav=null;
        ContentValues values=new ContentValues();values.put(MediaStore.Audio.Media.DISPLAY_NAME,"Tallulah_Mix_"+System.currentTimeMillis()+".wav");
        values.put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav");values.put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/Tallulah Audio Lab");values.put(MediaStore.Audio.Media.IS_PENDING,1);
        dest=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);if(dest==null)throw new Exception("Cannot create destination");
        try(InputStream in=new java.io.FileInputStream(temp);OutputStream out=getContentResolver().openOutputStream(dest)){if(out==null)throw new Exception("Cannot write destination");byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
        values.clear();values.put(MediaStore.Audio.Media.IS_PENDING,0);getContentResolver().update(dest,values,null,null);
        runOnUiThread(()->status.setText("Saved edited WAV to Music/Tallulah Audio Lab"));
      }catch(Exception e){if(dest!=null)getContentResolver().delete(dest,null,null);String message=e.getMessage();runOnUiThread(()->status.setText("Export failed: "+message));}
      finally{if(wav!=null)try{wav.close();}catch(Exception ignored){}if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}if(extractor!=null)extractor.release();if(temp!=null)temp.delete();}
    },"AudioExport").start();
  }
  private String clock(int ms){
    int seconds=Math.max(0,ms)/1000;
    return String.format(java.util.Locale.US,"%d:%02d",seconds/60,seconds%60);
  }
  private void showTrimEditor(Track t){
    int duration=Math.max(1,t.player.getDuration());
    LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(32,16,32,12);
    TextView original=text("Original duration: "+clock(duration),14,false);form.addView(original);
    TextView startLabel=text("",15,true);form.addView(startLabel);
    SeekBar startBar=new SeekBar(this);startBar.setMax(duration);startBar.setProgress(t.trimStartMs);form.addView(startBar);
    TextView endLabel=text("",15,true);form.addView(endLabel);
    SeekBar endBar=new SeekBar(this);endBar.setMax(duration);endBar.setProgress(t.trimEndMs>0?t.trimEndMs:duration);form.addView(endBar);
    TextView selected=text("",17,true);selected.setTextColor(accent);selected.setPadding(0,20,0,8);form.addView(selected);
    TextView hint=text("Move the START and END sliders to select the part you want to keep. Changes are previewed here before applying.",13,false);form.addView(hint);
    final Runnable update=()->{
      int start=startBar.getProgress(),end=endBar.getProgress();
      startLabel.setText("Start: "+clock(start));
      endLabel.setText("End: "+clock(end));
      selected.setText("Selected clip: "+clock(start)+" – "+clock(end)+"   •   Length: "+clock(end-start));
    };
    SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
        if(bar==startBar&&progress>=endBar.getProgress())startBar.setProgress(Math.max(0,endBar.getProgress()-1000));
        if(bar==endBar&&progress<=startBar.getProgress())endBar.setProgress(Math.min(duration,startBar.getProgress()+1000));
        update.run();
      }
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){if(bar==startBar)t.player.seekTo(startBar.getProgress());}
    };
    startBar.setOnSeekBarChangeListener(listener);endBar.setOnSeekBarChangeListener(listener);update.run();
    new android.app.AlertDialog.Builder(this).setTitle("Trim audio visually").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Apply trim",(dialog,which)->{
      int start=startBar.getProgress(),end=endBar.getProgress();
      if(end<=start){status.setText("Choose an end after the start");return;}
      createTrimmedFile(t,start,end);
    }).show();
  }
  private void createTrimmedFile(Track source,int startMs,int endMs){
    status.setText("Creating trimmed WAV clip…");
    new Thread(()->{
      File output=null;MediaExtractor extractor=null;MediaCodec decoder=null;
      try{
        extractor=new MediaExtractor();extractor.setDataSource(this,source.uri,null);
        int track=-1;MediaFormat format=null;
        for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;format=f;break;}}
        if(track<0)throw new Exception("No audio stream");
        extractor.selectTrack(track);
        decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));decoder.configure(format,null,null,0);decoder.start();
        extractor.seekTo(startMs*1000L,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        output=new File(getCacheDir(),"trim_"+System.currentTimeMillis()+".wav");
        RandomAccessFile wav=new RandomAccessFile(output,"rw");wav.setLength(0);wav.write(new byte[44]);
        int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        long bytes=0;boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(!outputDone){
          if(!inputDone){int index=decoder.dequeueInputBuffer(10000);if(index>=0){ByteBuffer in=decoder.getInputBuffer(index);in.clear();int size=extractor.readSampleData(in,0);long time=extractor.getSampleTime();if(size<0||time>=endMs*1000L){decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{decoder.queueInputBuffer(index,0,size,Math.max(0,time),0);extractor.advance();}}}
          int index=decoder.dequeueOutputBuffer(info,10000);
          if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat decoded=decoder.getOutputFormat();rate=decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT);if(decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)&&decoded.getInteger(MediaFormat.KEY_PCM_ENCODING)!=2)throw new Exception("Unsupported PCM encoding");}
          else if(index>=0){
            if(info.size>0&&info.presentationTimeUs>=startMs*1000L&&info.presentationTimeUs<endMs*1000L){
              ByteBuffer pcm=decoder.getOutputBuffer(index);pcm.position(info.offset);pcm.limit(info.offset+info.size);
              byte[] chunk=new byte[info.size];pcm.get(chunk);wav.write(chunk);bytes+=chunk.length;
            }
            if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outputDone=true;
            decoder.releaseOutputBuffer(index,false);
          }
        }
        if(bytes==0||bytes>0xffffffffL-36)throw new Exception("No supported audio samples");
        wav.seek(0);wav.writeBytes("RIFF");writeLe(wav,(int)(36+bytes));wav.writeBytes("WAVEfmt ");writeLe(wav,16);writeShortLe(wav,1);writeShortLe(wav,channels);writeLe(wav,rate);writeLe(wav,rate*channels*2);writeShortLe(wav,channels*2);writeShortLe(wav,16);wav.writeBytes("data");writeLe(wav,(int)bytes);wav.close();
        File finished=output;
        runOnUiThread(()->{
          try{
            int position=tracks.indexOf(source);
            if(position<0)return;
            boolean wasLooping=source.loop!=null&&source.loop.isChecked();
            int previousVolume=source.volume!=null?source.volume.getProgress():100;
            load(Uri.fromFile(finished));
            Track added=tracks.remove(tracks.size()-1);tracks.add(Math.min(position,tracks.size()),added);
            added.volume.setProgress(previousVolume);added.trimStartMs=0;added.trimEndMs=added.player.getDuration();added.loop.setChecked(wasLooping);added.player.setLooping(wasLooping);
            source.player.pause();source.player.release();tracks.remove(source);trackList.removeView(source.card);
            updateVolumes();
            status.setText("New trimmed clip: "+clock(added.player.getDuration())+" • original phone file untouched");
          }catch(Exception e){status.setText("Trim created but could not load: "+e.getMessage());}
        });
      }catch(Exception e){if(output!=null)output.delete();String message=e.getMessage();runOnUiThread(()->status.setText("Trim failed; original kept: "+message));}
      finally{if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}if(extractor!=null)extractor.release();}
    },"TrimAudio").start();
  }
  private void writeLe(RandomAccessFile f,int value)throws Exception{for(int i=0;i<4;i++)f.write((value>>(i*8))&255);}
  private void writeShortLe(RandomAccessFile f,int value)throws Exception{f.write(value&255);f.write((value>>8)&255);}
  private boolean hasSolo(){for(Track t:tracks)if(t.solo)return true;return false;}
  private void updateVolumes(){
    boolean any=hasSolo();
    for(Track t:tracks){
      try{
        float volume=(t.muted||(any&&!t.solo))?0f:t.volume.getProgress()/100f;
        int length=Math.max(1,t.trimEndMs-t.trimStartMs);
        int position=Math.max(0,Math.min(length,t.player.getCurrentPosition()-t.trimStartMs));
        // Fade gain is relative to the active clip, not the source file.
        // Apply even while paused so restarting at zero never leaks full volume.
        if(t.fadeInMs>0)volume*=Math.min(1f,position/(float)t.fadeInMs);
        if(t.fadeOutMs>0)volume*=Math.min(1f,(length-position)/(float)t.fadeOutMs);
        t.player.setVolume(Math.max(0f,volume),Math.max(0f,volume));
      }catch(Exception ignored){}
    }
  }
  private void seekAll(int delta){for(Track t:tracks){int target=Math.max(0,Math.min(t.player.getDuration(),t.player.getCurrentPosition()+delta));t.player.seekTo(target);}}

  private synchronized void startAtmosphereIfNeeded(){
    if(atmosphereThread!=null||(!atmosphereOn&&!stereoOn))return;
    atmosphereThread=new Thread(()->{
      AudioTrack output=null;
      try{
        int rate=22050,n=1024;
        output=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(n*16).setTransferMode(AudioTrack.MODE_STREAM).build();
        atmosphereTrack=output;output.play();short[] pcm=new short[n*2];long index=0;
        while(atmosphereOn||stereoOn){
          for(int i=0;i<n;i++,index++){
            double t=index/(double)rate,l=0,r=0;
            if(stereoOn){double g=stereoVolume/500.0;l=g*Math.sin(2*Math.PI*stereoCarrier*t);r=g*Math.sin(2*Math.PI*(stereoCarrier+stereoBeat)*t);}
            if(atmosphereOn){
              double w=0;
              switch(atmosphereType){
                case 0:w=0.6*Math.sin(2*Math.PI*65*t)+0.3*Math.sin(2*Math.PI*97*t);break;
                case 1:w=(0.4+0.3*Math.sin(2*Math.PI*0.08*t))*Math.sin(2*Math.PI*135*t);break;
                case 2:w=0.35*Math.sin(2*Math.PI*330*t+2*Math.sin(2*Math.PI*0.2*t));break;
                default:w=Math.pow(Math.max(0,Math.sin(2*Math.PI*1.2*t)),8)*Math.sin(2*Math.PI*90*t);break;
              }
              l+=w*atmosphereVolume/300.0;r+=w*atmosphereVolume/300.0;
            }
            pcm[2*i]=(short)(Math.max(-1,Math.min(1,l))*24000);
            pcm[2*i+1]=(short)(Math.max(-1,Math.min(1,r))*24000);
          }
          output.write(pcm,0,pcm.length);
        }
      }catch(Exception ignored){}finally{
        if(output!=null){try{output.stop();}catch(Exception ignored){}output.release();}
        atmosphereTrack=null;atmosphereThread=null;
      }
    },"DreamAudio");
    atmosphereThread.start();
  }
  private void resetRhythmTone(){
    if(rhythmTone!=null){rhythmTone.release();rhythmTone=null;}
    if(rhythmVolume<=0)return;
    int rate=44100, samples=rate*140/1000;
    byte[] pcm=new byte[samples*2];
    for(int i=0;i<samples;i++){
      double t=i/(double)rate;
      double env=Math.pow(1.0-Math.min(1.0,t/0.14),3);
      double attack=Math.min(1.0,t/0.012);
      double wave=Math.sin(2*Math.PI*(65*t+20*t*t));
      short v=(short)(wave*env*attack*20000);
      pcm[i*2]=(byte)(v&255);pcm[i*2+1]=(byte)((v>>8)&255);
    }
    try{
      rhythmTone=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length).build();
      rhythmTone.write(pcm,0,pcm.length);
      rhythmTone.setVolume(Math.min(1f,(rhythmVolume/100f)*(rhythmPunch/100f)));
    }catch(Exception e){rhythmTone=null;if(rhythmStatus!=null)rhythmStatus.setText("Pulse audio unavailable");}
  }
  private void playSoftPulse(){
    if(rhythmTone==null)return;
    try{rhythmTone.pause();rhythmTone.setPlaybackHeadPosition(0);rhythmTone.play();}catch(Exception ignored){}
  }
  @Override protected void onDestroy(){clipHandler.removeCallbacks(clipMonitor);atmosphereOn=false;stereoOn=false;rhythmEnabled=false;rhythmHandler.removeCallbacks(rhythmTask);if(rhythmTone!=null){rhythmTone.release();rhythmTone=null;}for(Track t:tracks)t.player.release();tracks.clear();super.onDestroy();}
}
