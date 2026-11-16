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

public class MainActivity extends Activity {
  private static final int PICK_AUDIO = 51;
  private final ArrayList<Track> tracks = new ArrayList<>();
  private LinearLayout trackList;
  private TextView status;
  private boolean playing = false;
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
    MediaPlayer player; Uri uri; SeekBar volume; CheckBox loop; TextView label; boolean muted=false, solo=false; int trimStartMs=0,trimEndMs=0;
    Track(MediaPlayer p,Uri u){player=p;uri=u;}
  }
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
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
    play.setOnClickListener(v->{if(tracks.isEmpty()){status.setText("Add an audio file first.");return;}int count=0;for(Track t:tracks){try{if(!hasSolo()||t.solo){if(t.player.getCurrentPosition()>=t.trimEndMs)t.player.seekTo(t.trimStartMs);t.player.start();count++;}}catch(Exception e){status.setText("Playback error: "+e.getMessage());}}if(count>0){playing=true;status.setText("Playing "+count+" track(s)");}});
    pause.setOnClickListener(v->{for(Track t:tracks)if(t.player.isPlaying())t.player.pause();playing=false;status.setText("Paused");});
    reset.setOnClickListener(v->{for(Track t:tracks){t.player.pause();t.player.seekTo(t.trimStartMs);}playing=false;status.setText("Reset to start");});
    status=text("Ready to import audio",14,false);status.setTextColor(0xffcbb5d5);status.setPadding(0,16,0,14);root.addView(status);
    LinearLayout jump=new LinearLayout(this);root.addView(jump);
    Button back=button("−15 sec");jump.addView(back,new LinearLayout.LayoutParams(0,-2,1));back.setOnClickListener(v->seekAll(-15000));
    Button middle=button("Middle");jump.addView(middle,new LinearLayout.LayoutParams(0,-2,1));middle.setOnClickListener(v->{for(Track t:tracks)t.player.seekTo(t.player.getDuration()/2);});
    Button forward=button("+15 sec");jump.addView(forward,new LinearLayout.LayoutParams(0,-2,1));forward.setOnClickListener(v->seekAll(15000));
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
      LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,12,0,0);trackList.addView(card,cp);
      String name=uri.getLastPathSegment();if(name==null)name="Audio track "+tracks.size();
      t.label=text(name,15,true);t.label.setSingleLine(true);t.label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);card.addView(t.label);
      TextView duration=text("Duration: "+(player.getDuration()/60000)+":"+String.format(java.util.Locale.US,"%02d",(player.getDuration()/1000)%60),12,false);card.addView(duration);
      TextView vlabel=text("Layer volume 100%",13,false);card.addView(vlabel);
      SeekBar vol=new SeekBar(this);vol.setMax(100);vol.setProgress(100);card.addView(vol);t.volume=vol;
      vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){float level=p/100f;updateVolumes();vlabel.setText("Layer volume "+p+"%");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
      LinearLayout row=new LinearLayout(this);row.setOrientation(0);card.addView(row);
      CheckBox loop=new CheckBox(this);loop.setText("Loop");loop.setTextColor(Color.WHITE);row.addView(loop);t.loop=loop;loop.setOnCheckedChangeListener((b,on)->player.setLooping(on));
      Button solo=button("▶ Track");row.addView(solo);solo.setOnClickListener(v->{if(player.isPlaying()){player.pause();solo.setText("▶ Track");}else{player.start();solo.setText("Ⅱ Track");}});
      Button mute=button("Mute");row.addView(mute);mute.setOnClickListener(v->{t.muted=!t.muted;mute.setText(t.muted?"Unmute":"Mute");updateVolumes();});
      Button isolate=button("Solo");row.addView(isolate);isolate.setOnClickListener(v->{t.solo=!t.solo;isolate.setText(t.solo?"Unsolo":"Solo");updateVolumes();});
      TextView seekTitle=text("Preview position",13,false);card.addView(seekTitle);
      SeekBar seek=new SeekBar(this);seek.setMax(Math.max(1,player.getDuration()));seek.setProgress(0);card.addView(seek);
      seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){if(user)player.seekTo(p);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
      Button clipEdit=button("✂ Trim clip");card.addView(clipEdit);clipEdit.setOnClickListener(v->showTrimEditor(t));
      TextView speedLabel=text("Speed 1.00×",13,false);card.addView(speedLabel);
      SeekBar speed=new SeekBar(this);speed.setMax(150);speed.setProgress(50);card.addView(speed);
      speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){float rate=0.5f+p/100f;speedLabel.setText(String.format(java.util.Locale.US,"Speed %.2f×",rate));try{player.setPlaybackParams(player.getPlaybackParams().setSpeed(rate));}catch(Exception ignored){}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
      Button remove=button("✕");row.addView(remove);remove.setOnClickListener(v->{player.release();tracks.remove(t);trackList.removeView(card);status.setText("Track removed");});
      status.setText("Imported "+tracks.size()+" track(s)");
    }catch(Exception e){status.setText("Could not open audio: "+e.getMessage());}
  }
  private void showTrimEditor(Track t){
    LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(30,12,30,12);
    TextView instruction=text("Set the portion of this audio to play, in seconds.",14,false);form.addView(instruction);
    EditText start=new EditText(this);start.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);start.setText(String.valueOf(t.trimStartMs/1000));start.setHint("Start seconds");form.addView(start);
    EditText end=new EditText(this);end.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);end.setText(String.valueOf(t.trimEndMs/1000));end.setHint("End seconds");form.addView(end);
    new android.app.AlertDialog.Builder(this).setTitle("Trim audio").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Apply",(dialog,which)->{
      try{
        long a=Long.parseLong(start.getText().toString())*1000L;
        long z=Long.parseLong(end.getText().toString())*1000L;
        if(a<0||z<=a||z>t.player.getDuration()){status.setText("Invalid trim range");return;}
        t.trimStartMs=(int)a;t.trimEndMs=(int)z;t.player.seekTo(t.trimStartMs);status.setText("Trim saved for playback");
      }catch(Exception ex){status.setText("Enter valid whole seconds");}
    }).show();
  }
  private boolean hasSolo(){for(Track t:tracks)if(t.solo)return true;return false;}
  private void updateVolumes(){boolean any=hasSolo();for(Track t:tracks){float v=(t.muted||(any&&!t.solo))?0:t.volume.getProgress()/100f;try{t.player.setVolume(v,v);}catch(Exception ignored){}}}
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
  @Override protected void onDestroy(){atmosphereOn=false;stereoOn=false;rhythmEnabled=false;rhythmHandler.removeCallbacks(rhythmTask);if(rhythmTone!=null){rhythmTone.release();rhythmTone=null;}for(Track t:tracks)t.player.release();tracks.clear();super.onDestroy();}
}
