package com.tallulah.audiolab2;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.media.MediaPlayer;
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
  private final int bg = Color.rgb(16,13,24), panel = Color.rgb(34,25,45), accent = Color.rgb(187,115,213);
  private static class Track {
    MediaPlayer player; Uri uri; SeekBar volume; CheckBox loop; TextView label;
    Track(MediaPlayer p,Uri u){player=p;uri=u;}
  }
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    ScrollView scroll = new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,30,24,24);scroll.addView(root);setContentView(scroll);
    TextView heading=text("TALLULAH",28,true);heading.setTextColor(accent);root.addView(heading);
    TextView sub=text("AUDIO LAB 3.0  •  NATIVE ALPHA",13,false);sub.setTextColor(0xffc6a6cf);root.addView(sub);
    TextView info=text("Load audio from your phone. Each track has independent volume and looping.",15,false);info.setPadding(0,22,0,18);root.addView(info);
    Button add=button("＋  ADD AUDIO TRACK");root.addView(add);
    add.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,PICK_AUDIO);});
    LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);root.addView(controls);
    Button play=button("▶ PLAY ALL");controls.addView(play,new LinearLayout.LayoutParams(0,-2,1));
    Button pause=button("Ⅱ PAUSE");controls.addView(pause,new LinearLayout.LayoutParams(0,-2,1));
    Button reset=button("↺ RESET");controls.addView(reset,new LinearLayout.LayoutParams(0,-2,1));
    play.setOnClickListener(v->{if(tracks.isEmpty()){status.setText("Add an audio file first.");return;}int count=0;for(Track t:tracks){try{t.player.start();count++;}catch(Exception e){status.setText("Playback error: "+e.getMessage());}}if(count>0){playing=true;status.setText("Playing "+count+" track(s)");}});
    pause.setOnClickListener(v->{for(Track t:tracks)if(t.player.isPlaying())t.player.pause();playing=false;status.setText("Paused");});
    reset.setOnClickListener(v->{for(Track t:tracks){t.player.pause();t.player.seekTo(0);}playing=false;status.setText("Reset to start");});
    status=text("Ready to import audio",14,false);status.setTextColor(0xffcbb5d5);status.setPadding(0,16,0,14);root.addView(status);
    TextView title=text("YOUR TRACKS",18,true);title.setTextColor(accent);root.addView(title);
    trackList=new LinearLayout(this);trackList.setOrientation(1);root.addView(trackList);
    TextView foot=text("This first build tests native playback. Dream Voice, binaural beats, export, and Govee controls are not included yet.",13,false);foot.setTextColor(0xffb7a5be);foot.setPadding(0,28,0,0);root.addView(foot);
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
      Track t=new Track(player,uri);tracks.add(t);
      LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(16,16,16,16);
      GradientDrawable shape=new GradientDrawable();shape.setColor(panel);shape.setCornerRadius(22);card.setBackground(shape);
      LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,12,0,0);trackList.addView(card,cp);
      String name=uri.getLastPathSegment();if(name==null)name="Audio track "+tracks.size();
      t.label=text(name,15,true);t.label.setSingleLine(true);t.label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);card.addView(t.label);
      TextView duration=text("Duration: "+(player.getDuration()/60000)+":"+String.format(java.util.Locale.US,"%02d",(player.getDuration()/1000)%60),12,false);card.addView(duration);
      TextView vlabel=text("Volume 100%",13,false);card.addView(vlabel);
      SeekBar vol=new SeekBar(this);vol.setMax(100);vol.setProgress(100);card.addView(vol);t.volume=vol;
      vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){float level=p/100f;player.setVolume(level,level);vlabel.setText("Volume "+p+"%");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
      LinearLayout row=new LinearLayout(this);row.setOrientation(0);card.addView(row);
      CheckBox loop=new CheckBox(this);loop.setText("Loop");loop.setTextColor(Color.WHITE);row.addView(loop);t.loop=loop;loop.setOnCheckedChangeListener((b,on)->player.setLooping(on));
      Button solo=button("▶ Track");row.addView(solo);solo.setOnClickListener(v->{if(player.isPlaying()){player.pause();solo.setText("▶ Track");}else{player.start();solo.setText("Ⅱ Track");}});
      Button remove=button("✕");row.addView(remove);remove.setOnClickListener(v->{player.release();tracks.remove(t);trackList.removeView(card);status.setText("Track removed");});
      status.setText("Imported "+tracks.size()+" track(s)");
    }catch(Exception e){status.setText("Could not open audio: "+e.getMessage());}
  }
  @Override protected void onDestroy(){for(Track t:tracks)t.player.release();tracks.clear();super.onDestroy();}
}
