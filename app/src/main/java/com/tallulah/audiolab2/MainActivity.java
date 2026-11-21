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
  private static final int BLE_PERMISSION_REQUEST=861;
  private android.bluetooth.le.BluetoothLeScanner hismithScanner;
  private android.bluetooth.le.ScanCallback hismithScanCallback;
  private android.bluetooth.BluetoothGatt hismithGatt;
  private TextView hismithBleStatus;
  private LinearLayout hismithBleResults;
  private final java.util.HashSet<String> hismithSeen=new java.util.HashSet<>();
  private final Handler hismithBleHandler=new Handler(Looper.getMainLooper());
  private boolean hismithScanning=false;
  private ArrayList<String> hismithLightTargetsSource;
  private final Handler hismithLightHandler=new Handler(Looper.getMainLooper());
  private final ArrayList<String> hismithLightTargets=new ArrayList<>();
  private boolean hismithLightSyncActive=false;
  private boolean hismithLightHigh=false;
  private int hismithLightOffsetMs=0;
  private TextView hismithLightSyncStatus;
  private long hismithLightNextTick=0;
  private final Runnable hismithLightTick=new Runnable(){
    @Override public void run(){
      if(!hismithLightSyncActive)return;
      if(!hismithMonitorActive||hismithReportedSetting<1||hismithLightTargets.isEmpty()){
        stopHismithLightSync();
        if(hismithLightSyncStatus!=null)hismithLightSyncStatus.setText("Sync paused: no valid Bluetooth speed or lights.");
        return;
      }
      // One measured reference: setting 50 ~= 2.45 cycles/s. Linear scaling is provisional.
      double bps=2.45*hismithReportedSetting/50.0;
      long halfCycle=Math.max(110,Math.round(500.0/bps));
      hismithLightHigh=!hismithLightHigh;
      sendGoveeCommand(new ArrayList<>(hismithLightTargets),"brightness",hismithLightHigh?60:15,null);
      if(hismithLightSyncStatus!=null)hismithLightSyncStatus.setText(
        String.format(java.util.Locale.US,"Live Sync ON • setting %d • estimated %.2f BPS • offset %+d ms • provisional timing",
          hismithReportedSetting,bps,hismithLightOffsetMs));
      hismithLightNextTick+=halfCycle;
      hismithLightHandler.postDelayed(this,Math.max(10,hismithLightNextTick-android.os.SystemClock.uptimeMillis()));
    }
  };
  private void startHismithGoveeSync(){
      if(!hismithMonitorActive||hismithReportedSetting<1){hismithLightSyncStatus.setText("Start the Bluetooth live monitor and receive a nonzero setting first.");return;}
      if(hismithLightTargetsSource==null||hismithLightTargetsSource.isEmpty()){hismithLightSyncStatus.setText("Find Govee lights first.");return;}
      stopReactive();stopGoveeFlow();stopGoveeStrobe();stopHismithLightSync();
      hismithLightTargets.addAll(hismithLightTargetsSource);
      hismithLightSyncActive=true;hismithLightHigh=false;
      sendGoveeCommand(new ArrayList<>(hismithLightTargets),"turn",1,null);
      hismithLightNextTick=android.os.SystemClock.uptimeMillis()+Math.max(0,hismithLightOffsetMs);
      hismithLightHandler.postDelayed(hismithLightTick,Math.max(0,hismithLightOffsetMs));
    
  }
  private void stopHismithLightSync(){
    hismithLightSyncActive=false;
    hismithLightHandler.removeCallbacks(hismithLightTick);
    hismithLightTargets.clear();
    if(hismithLightSyncStatus!=null)hismithLightSyncStatus.setText("Live Sync OFF");
  }
  private TextView hismithLiveStatus;
  private TextView hismithCandidateStatus;
  private TextView hismithDashboard;
  private EditText hismithMeasuredBpsInput;
  private int hismithReportedSetting=-1;
  private int hismithCalibrationSetting=-1;
  private double hismithCalibrationBps=0;
  private void renderHismithDashboard(){
    if(hismithDashboard==null)return;
    String setting=hismithReportedSetting<0?"Waiting for Bluetooth":"Setting "+hismithReportedSetting+" / 100";
    String estimate="Rhythm frequency: not calibrated";
    if(hismithCalibrationSetting>0&&hismithCalibrationBps>0&&hismithReportedSetting>=0){
      estimate=String.format(java.util.Locale.US,"Estimated %.2f BPS (proportional model, unverified)",hismithCalibrationBps*hismithReportedSetting/hismithCalibrationSetting);
    }
    hismithDashboard.setText("HISMITH LIVE SPEED\n"+setting+"\n"+estimate+"\nBluetooth reports a setting, not actual motion.");
  }
  private TextView hismithHistoryStatus;
  private final java.util.ArrayDeque<String> hismithHistory=new java.util.ArrayDeque<>();
  private void renderHismithHistory(){
    if(hismithHistoryStatus==null)return;
    StringBuilder b=new StringBuilder("Recent BLE messages (newest first):");
    for(String line:hismithHistory)b.append("\n").append(line);
    hismithHistoryStatus.setText(b.toString());
  }
  private final java.util.ArrayDeque<android.bluetooth.BluetoothGattCharacteristic> hismithNotifyQueue=new java.util.ArrayDeque<>();
  private boolean hismithMonitorActive=false;
  private int hismithPacketCount=0;
  private String hismithLastPacket="None";
  private static final java.util.UUID BLE_CCCD=java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  private String bleHex(byte[] data){
    if(data==null)return "(empty)";
    StringBuilder b=new StringBuilder();
    for(int i=0;i<Math.min(data.length,48);i++)b.append(String.format(java.util.Locale.US,"%02X ",data[i]&255));
    return b.toString().trim()+(data.length>48?" …":"");
  }
  private void nextHismithSubscription(android.bluetooth.BluetoothGatt g){
    if(!hismithMonitorActive||g!=hismithGatt)return;
    android.bluetooth.BluetoothGattCharacteristic c=hismithNotifyQueue.poll();
    if(c==null){runOnUiThread(()->hismithLiveStatus.setText("Monitoring • "+hismithPacketCount+" notifications • Last: "+hismithLastPacket));return;}
    try{
      android.bluetooth.BluetoothGattDescriptor d=c.getDescriptor(BLE_CCCD);
      if(d==null||!g.setCharacteristicNotification(c,true)){nextHismithSubscription(g);return;}
      byte[] enable=((c.getProperties()&android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0)?
        android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
      if(android.os.Build.VERSION.SDK_INT>=33){
        if(g.writeDescriptor(d,enable)!=android.bluetooth.BluetoothStatusCodes.SUCCESS)nextHismithSubscription(g);
      }else{
        d.setValue(enable);
        if(!g.writeDescriptor(d))nextHismithSubscription(g);
      }
    }catch(Exception e){nextHismithSubscription(g);}
  }
  private void startHismithMonitor(){
    if(hismithGatt==null||hismithGatt.getServices().isEmpty()){
      hismithLiveStatus.setText("Connect to HISMITH and discover services first.");return;
    }
    hismithNotifyQueue.clear();hismithPacketCount=0;hismithLastPacket="None";hismithMonitorActive=true;
    hismithReportedSetting=-1;renderHismithDashboard();
    hismithHistory.clear();renderHismithHistory();
    if(hismithCandidateStatus!=null)hismithCandidateStatus.setText("Candidate speed: waiting for AA 03 packets (unverified)");
    for(android.bluetooth.BluetoothGattService service:hismithGatt.getServices()){
      String serviceId=service.getUuid().toString();
      if(!serviceId.startsWith("0000ffe0-")&&!serviceId.startsWith("0000ffc0-"))continue;
      for(android.bluetooth.BluetoothGattCharacteristic c:service.getCharacteristics()){
        int p=c.getProperties();
        if((p&(android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY|android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE))!=0&&c.getDescriptor(BLE_CCCD)!=null)hismithNotifyQueue.add(c);
      }
    }
    hismithLiveStatus.setText("Subscribing to "+hismithNotifyQueue.size()+" notification characteristic(s). No motor commands.");
    nextHismithSubscription(hismithGatt);
  }

  private boolean hismithPermissionOk(){
    if(android.os.Build.VERSION.SDK_INT>=31)return checkSelfPermission("android.permission.BLUETOOTH_SCAN")==android.content.pm.PackageManager.PERMISSION_GRANTED&&checkSelfPermission("android.permission.BLUETOOTH_CONNECT")==android.content.pm.PackageManager.PERMISSION_GRANTED;
    return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED;
  }
  private void stopHismithScan(){
    if(hismithScanner!=null&&hismithScanCallback!=null&&hismithPermissionOk()){
      try{hismithScanner.stopScan(hismithScanCallback);}catch(Exception ignored){}
    }
    hismithScanning=false;
  }
  private void closeHismithGatt(){
    stopHismithLightSync();
    hismithMonitorActive=false;hismithNotifyQueue.clear();
    if(hismithGatt!=null){try{hismithGatt.disconnect();hismithGatt.close();}catch(Exception ignored){}hismithGatt=null;}
  }
  private void startHismithScan(){
    if(!hismithPermissionOk()){
      if(android.os.Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{"android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT"},BLE_PERMISSION_REQUEST);
      else requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},BLE_PERMISSION_REQUEST);
      hismithBleStatus.setText("Allow Nearby devices permission, then tap Scan again.");
      return;
    }
    android.bluetooth.BluetoothManager manager=(android.bluetooth.BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
    android.bluetooth.BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
    if(adapter==null||!adapter.isEnabled()){hismithBleStatus.setText("Enable Bluetooth in phone settings first.");return;}
    stopHismithScan();hismithSeen.clear();hismithBleResults.removeAllViews();
    hismithScanner=adapter.getBluetoothLeScanner();
    if(hismithScanner==null){hismithBleStatus.setText("BLE scanning unavailable.");return;}
    hismithScanCallback=new android.bluetooth.le.ScanCallback(){
      @Override public void onScanResult(int callbackType,android.bluetooth.le.ScanResult result){
        android.bluetooth.BluetoothDevice device=result.getDevice();
        if(device==null)return;
        String address=device.getAddress();
        if(!hismithSeen.add(address))return;
        String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();
        if(name==null||name.isEmpty())try{name=device.getName();}catch(SecurityException ignored){}
        final String shown=(name==null||name.isEmpty()?"Unnamed BLE device":name);
        runOnUiThread(()->{
          Button item=button(shown+" • "+address+" • "+result.getRssi()+" dBm");
          hismithBleResults.addView(item);
          item.setOnClickListener(v->inspectHismithDevice(device,shown));
          hismithBleStatus.setText("Found "+hismithSeen.size()+" BLE device(s). Tap one to inspect services.");
        });
      }
      @Override public void onScanFailed(int code){runOnUiThread(()->{hismithScanning=false;hismithBleStatus.setText("Scan failed: "+code);});}
    };
    try{
      hismithScanner.startScan(hismithScanCallback);hismithScanning=true;
      hismithBleStatus.setText("Scanning BLE advertisements for 12 seconds. No motor commands sent.");
      hismithBleHandler.postDelayed(()->{if(hismithScanning){stopHismithScan();hismithBleStatus.setText("Scan complete • "+hismithSeen.size()+" BLE device(s). Tap a result to inspect.");}},12000);
    }catch(Exception e){hismithBleStatus.setText("Scan unavailable: "+e.getClass().getSimpleName());}
  }
  private void inspectHismithDevice(android.bluetooth.BluetoothDevice device,String name){
    if(!hismithPermissionOk()){hismithBleStatus.setText("Bluetooth permission needed.");return;}
    stopHismithScan();closeHismithGatt();
    hismithBleStatus.setText("Connecting to "+name+" for service discovery only. No writes.");
    try{
      hismithGatt=device.connectGatt(this,false,new android.bluetooth.BluetoothGattCallback(){
        @Override public void onConnectionStateChange(android.bluetooth.BluetoothGatt g,int status,int state){
          if(status==android.bluetooth.BluetoothGatt.GATT_SUCCESS&&state==android.bluetooth.BluetoothProfile.STATE_CONNECTED){
            try{g.discoverServices();}catch(Exception e){runOnUiThread(()->hismithBleStatus.setText("Service discovery failed."));}
          }else if(state==android.bluetooth.BluetoothProfile.STATE_DISCONNECTED){
            runOnUiThread(()->hismithBleStatus.setText("Disconnected from "+name+" (status "+status+")."));
          }
        }
        @Override public void onDescriptorWrite(android.bluetooth.BluetoothGatt g,android.bluetooth.BluetoothGattDescriptor d,int status){
          nextHismithSubscription(g);
        }
        @Override public void onCharacteristicChanged(android.bluetooth.BluetoothGatt g,android.bluetooth.BluetoothGattCharacteristic c,byte[] value){
          if(!hismithMonitorActive||g!=hismithGatt)return;
          final byte[] packet=value==null?new byte[0]:value.clone();
          final String characteristic=c.getUuid().toString().substring(4,8);
          runOnUiThread(()->{
            if(!hismithMonitorActive||g!=hismithGatt)return;
            hismithPacketCount++;
            hismithLastPacket=characteristic+" : "+bleHex(packet);
            if(hismithLiveStatus!=null)hismithLiveStatus.setText("Notifications: "+hismithPacketCount+"\nLast: "+hismithLastPacket);
            String time=new java.text.SimpleDateFormat("HH:mm:ss.SSS",java.util.Locale.US).format(new java.util.Date());
            hismithHistory.addFirst(time+" • "+hismithLastPacket);
            while(hismithHistory.size()>25)hismithHistory.removeLast();
            renderHismithHistory();
            if(hismithCandidateStatus!=null&&packet.length==4&&(packet[0]&255)==0xAA&&(packet[1]&255)==0x03){
              int candidate=packet[2]&255;
              if(candidate<=100){hismithReportedSetting=candidate;renderHismithDashboard();}
              boolean matching=((candidate+3)&255)==(packet[3]&255);
              hismithCandidateStatus.setText("Candidate value: "+candidate+" / 100? (UNVERIFIED) • final-byte pattern "+(matching?"matches":"differs")+". Not measured thrust speed.");
            }
          });
        }
        @Override public void onCharacteristicChanged(android.bluetooth.BluetoothGatt g,android.bluetooth.BluetoothGattCharacteristic c){
          onCharacteristicChanged(g,c,c.getValue());
        }
        @Override public void onServicesDiscovered(android.bluetooth.BluetoothGatt g,int status){
          runOnUiThread(()->{
            if(status!=android.bluetooth.BluetoothGatt.GATT_SUCCESS){hismithBleStatus.setText("Service discovery status: "+status);return;}
            StringBuilder details=new StringBuilder("Services for "+name+" (read-only inspection):");
            for(android.bluetooth.BluetoothGattService service:g.getServices()){
              details.append("\nService ").append(service.getUuid());
              for(android.bluetooth.BluetoothGattCharacteristic c:service.getCharacteristics()){
                details.append("\n  ").append(c.getUuid()).append(" properties 0x").append(Integer.toHexString(c.getProperties()));
              }
            }
            hismithBleStatus.setText(details.toString());
          });
        }
      },android.bluetooth.BluetoothDevice.TRANSPORT_LE);
    }catch(Exception e){hismithBleStatus.setText("Connection unavailable: "+e.getClass().getSimpleName());}
  }

  private static final int PICK_AUDIO = 51;
  private final ArrayList<Track> tracks = new ArrayList<>();
  private LinearLayout trackList;
  private TextView status;
  private SeekBar masterSeek;
  private TextView masterTime;
  private boolean draggingMaster=false;
  private boolean playing = false;
  private long mixClockStartedAt=0;
  private int mixClockPositionMs=0;
  private int mixPosition(){return playing?(int)Math.min(getMixDuration(),Math.max(0,android.os.SystemClock.elapsedRealtime()-mixClockStartedAt)):mixClockPositionMs;}
  private void positionMix(int ms){mixClockPositionMs=Math.max(0,Math.min(getMixDuration(),ms));if(playing)mixClockStartedAt=android.os.SystemClock.elapsedRealtime()-mixClockPositionMs;for(Track t:tracks){try{t.player.pause();int local=mixClockPositionMs-t.startAtMs;t.player.seekTo(t.trimStartMs+Math.max(0,Math.min(local,t.trimEndMs-t.trimStartMs)));t.scheduledActive=false;}catch(Exception ignored){}}updateVolumes();}
  private final Handler clipHandler=new Handler(Looper.getMainLooper());
  private final Runnable clipMonitor=new Runnable(){public void run(){if(playing){int now=mixPosition();for(Track t:tracks){try{int local=now-t.startAtMs;if(local>=0&&local<t.trimEndMs-t.trimStartMs&&!t.scheduledActive&&(!hasSolo()||t.solo)){t.player.seekTo(t.trimStartMs+local);updateVolumes();t.player.start();t.scheduledActive=true;}if(local>=t.trimEndMs-t.trimStartMs&&t.scheduledActive&&!t.loop.isChecked()){t.player.pause();t.scheduledActive=false;}}catch(Exception ignored){}}if(now>=getMixDuration()){playing=false;mixClockPositionMs=getMixDuration();}}for(Track t:tracks){try{if(t.player.isPlaying()&&t.trimEndMs<t.player.getDuration()-150&&t.player.getCurrentPosition()>=t.trimEndMs){if(t.loop.isChecked()){t.player.seekTo(t.trimStartMs);}else{t.player.pause();t.player.seekTo(t.trimStartMs);}}}catch(Exception ignored){}}updateVolumes();updateMasterTimeline();updateTrackProgress();clipHandler.postDelayed(this,80);}};
  private final java.util.HashMap<Integer,android.media.audiofx.Visualizer> levelVisualizers=new java.util.HashMap<>();
  private boolean levelTestActive=false;
  private ProgressBar audioLevelBar=null;
  private TextView audioLevelStatus=null;
  private int audioSensitivity=60;
  private int detectedAudioLevel=0;
  private double detectedRawLevel=0;
  private double detectedBassLevel=0;
  private boolean bassPulseMode=false;
  private double bassFloor=0;
  private double bassPeak=1;
  private int bassPulseStrength=75;
  private long lastBassPulseMs=0;
  private double reactiveBaseline=0;
  private double reactivePeak=0;
  private boolean reactiveActive=false;
  private final ArrayList<String> reactiveIps=new ArrayList<>();
  private int reactiveDelay=350;
  private double reactiveSmoothed=0;
  private int reactiveLast=-1;
  private TextView reactiveDiagnostic=null;
  private long reactiveLastSentAt=0;
  private int reactiveCommandCount=0;
  private int reactiveLastRequested=-1;
  private final Handler reactiveHandler=new Handler(Looper.getMainLooper());
  private final Runnable reactiveTick=new Runnable(){public void run(){
    if(!reactiveActive)return;
    if(!levelTestActive||reactiveIps.isEmpty()){stopReactive();return;}
    // Adaptive audio range: avoid pinning brightness at 100% on consistently loud tracks.
    double raw=detectedRawLevel;
    reactiveBaseline=reactiveBaseline*0.98+raw*0.02;
    reactivePeak=Math.max(raw,reactivePeak*0.97);
    double range=Math.max(2.0,reactivePeak-reactiveBaseline);
    double normalized=Math.max(0,Math.min(1,(raw-reactiveBaseline+range*0.2)/range));
    reactiveSmoothed=reactiveSmoothed*0.45+normalized*0.55;
    int brightness=Math.max(5,Math.min(100,(int)Math.round(5+reactiveSmoothed*95)));
    if(bassPulseMode){
      double bass=detectedBassLevel;
      bassFloor=bassFloor*0.96+bass*0.04;
      bassPeak=Math.max(bass,bassPeak*0.97);
      double bassRange=Math.max(0.7,bassPeak-bassFloor);
      double impact=Math.max(0,Math.min(1,(bass-bassFloor)/bassRange));
      long now=android.os.SystemClock.uptimeMillis();
      boolean hit=impact>0.58&&now-lastBassPulseMs>=Math.max(220,reactiveDelay);
      if(hit)lastBassPulseMs=now;
      brightness=hit?Math.min(100,20+bassPulseStrength):Math.max(5,Math.min(35,Math.round(8+(float)impact*20)));
    }
    if(reactiveDiagnostic!=null)reactiveDiagnostic.setText("Mode: "+(bassPulseMode?"Bass Pulse":"Brightness")+" • Audio: "+detectedAudioLevel+"% • Bass: "+String.format(java.util.Locale.US,"%.1f",detectedBassLevel)+" • Target: "+brightness+"% • Last sent: "+reactiveLastRequested+"% • Commands: "+reactiveCommandCount);
    if(Math.abs(brightness-reactiveLast)>=2){
      reactiveLast=brightness;
      reactiveLastRequested=brightness;
      reactiveCommandCount++;
      ArrayList<String> target=new ArrayList<>(reactiveIps);
      sendGoveeCommand(target,"brightness",brightness,null);
    }
    reactiveHandler.postDelayed(this,reactiveDelay);
  }};
  private void stopReactive(){
    reactiveActive=false;reactiveHandler.removeCallbacks(reactiveTick);
    reactiveIps.clear();reactiveLast=-1;
  }

  private final Handler levelHandler=new Handler(Looper.getMainLooper());
  private final Runnable levelMonitor=new Runnable(){public void run(){
    if(!levelTestActive)return;
    java.util.HashSet<Integer> activeSessions=new java.util.HashSet<>();
    for(Track t:tracks){
      try{
        if(t.player!=null&&t.player.isPlaying()){
          int session=t.player.getAudioSessionId();
          if(session>0)activeSessions.add(session);
        }
      }catch(Exception ignored){}
    }
    // Remove visualizers for tracks that stopped, were replaced or were removed.
    for(Integer old:new java.util.ArrayList<>(levelVisualizers.keySet())){
      if(!activeSessions.contains(old)){
        android.media.audiofx.Visualizer viz=levelVisualizers.remove(old);
        try{viz.setEnabled(false);viz.release();}catch(Exception ignored){}
      }
    }
    boolean permissionOk=android.os.Build.VERSION.SDK_INT>=23&&
      checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED;
    if(permissionOk){
      for(Integer session:activeSessions){
        if(levelVisualizers.containsKey(session))continue;
        android.media.audiofx.Visualizer viz=null;
        try{
          viz=new android.media.audiofx.Visualizer(session);
          viz.setCaptureSize(android.media.audiofx.Visualizer.getCaptureSizeRange()[0]);
          viz.setEnabled(true);
          levelVisualizers.put(session,viz);
        }catch(Exception e){
          if(viz!=null)try{viz.release();}catch(Exception ignored){}
        }
      }
    }
    // Combine each playing layer's RMS energy. Keep an uncapped reading
    // for adaptive lighting and a capped percentage for the visual meter.
    double combinedPower=0;
    double combinedBassPower=0;
    int captured=0;
    for(android.media.audiofx.Visualizer viz:levelVisualizers.values()){
      try{
        byte[] wave=new byte[viz.getCaptureSize()];
        if(viz.getWaveForm(wave)==android.media.audiofx.Visualizer.SUCCESS&&wave.length>0){
          double power=0;
          for(byte sample:wave){double v=(sample&255)-128;power+=v*v;}
          combinedPower+=power/wave.length;
          captured++;
          byte[] fft=new byte[viz.getCaptureSize()];
          if(viz.getFft(fft)==android.media.audiofx.Visualizer.SUCCESS){
            double hz=viz.getSamplingRate()/1000.0/fft.length;
            int low=Math.max(1,(int)Math.ceil(45/hz));
            int high=Math.min(fft.length/2-1,(int)Math.floor(220/hz));
            for(int k=low;k<=high;k++){
              double real=fft[k*2],imag=fft[k*2+1];
              combinedBassPower+=(real*real+imag*imag)/(fft.length*fft.length);
            }
          }
        }
      }catch(Exception ignored){}
    }
    detectedBassLevel=captured>0?Math.sqrt(combinedBassPower)*100*(audioSensitivity/100.0):0;
    detectedRawLevel=captured>0?Math.sqrt(combinedPower)/128.0*100.0*(audioSensitivity/100.0):0;
    detectedAudioLevel=Math.min(100,(int)Math.round(detectedRawLevel));
    if(audioLevelBar!=null)audioLevelBar.setProgress(detectedAudioLevel);
    if(audioLevelStatus!=null){
      if(activeSessions.isEmpty())audioLevelStatus.setText("Play an imported audio track to test detection.");
      else if(captured==0)audioLevelStatus.setText("Audio capture unavailable • check permission");
      else audioLevelStatus.setText("Mix detected • "+captured+" layer(s) • level "+detectedAudioLevel+"%");
    }
    levelHandler.postDelayed(this,120);
  }};
  private void releaseLevelVisualizer(){
    for(android.media.audiofx.Visualizer viz:levelVisualizers.values()){
      try{viz.setEnabled(false);viz.release();}catch(Exception ignored){}
    }
    levelVisualizers.clear();
    detectedRawLevel=0;detectedAudioLevel=0;detectedBassLevel=0;
  }
  private final Handler goveeFlowHandler=new Handler(Looper.getMainLooper());
  private Runnable goveeFlowTask=null;
  private boolean goveeFlowActive=false;
  private int goveeFlowStepMs=350;
  private ArrayList<String> goveeFlowTargets=new ArrayList<>();
  private int goveeFlowStep=0;
  private int goveeFlowMode=0;
  private final int[][] goveeFlowPalettes={
    {0xff5C001B,0xffA11B52,0xff592079,0xff300D45},
    {0xff001D60,0xff034D9A,0xff008B9D,0xff003D74}
  };
  private final Handler goveeStrobeHandler=new Handler(Looper.getMainLooper());
  private Runnable goveeStrobeTask=null;
  private boolean goveeStrobeActive=false;
  private int goveeStrobeIntervalMs=1000;
  private int goveeStrobeBrightness=50;
  private ArrayList<String> goveeStrobeTargets=new ArrayList<>();
  private final Handler rhythmHandler = new Handler(Looper.getMainLooper());
  private AudioTrack rhythmTone;
  private boolean rhythmEnabled=false,strokeSync=false;
  private int strokeOffsetPercent=0;
  private double preciseStrokeBps=0.0;
  private long strokeAnchorMs=0,strokeNextBeat=0;
  private long strokeCycleIndex=0;
  private int rhythmBpm=40, rhythmVolume=75, rhythmPunch=100, rhythmPattern=0, rhythmTick=0, rhythmSound=0;
  private TextView rhythmStatus;
  private AudioTrack atmosphereTrack;
  private Thread atmosphereThread;
  private volatile boolean atmosphereOn=false, stereoOn=false;
  private volatile int atmosphereType=0, atmosphereVolume=20, stereoVolume=25, stereoBeat=6, stereoCarrier=180;
  private double strokePeriodExactMs(){
    return 1000.0/(preciseStrokeBps>0?preciseStrokeBps:Math.max(1,rhythmBpm)/60.0);
  }
  private long strokePeriodMs(){return Math.max(100,Math.round(strokePeriodExactMs()));}
  private long strokeTargetMs(long cycle){
    return strokeAnchorMs+Math.round((cycle+strokeOffsetPercent/100.0)*strokePeriodExactMs());
  }
  private void resetStrokeClock(){
    strokeAnchorMs=android.os.SystemClock.uptimeMillis();
    strokeCycleIndex=0;
    rescheduleStrokeClock();
  }
  private void rescheduleStrokeClock(){
    long now=android.os.SystemClock.uptimeMillis();
    double period=strokePeriodExactMs();
    strokeCycleIndex=Math.max(0,(long)Math.ceil((now-strokeAnchorMs)/period-strokeOffsetPercent/100.0));
    strokeNextBeat=strokeTargetMs(strokeCycleIndex);
    rhythmHandler.removeCallbacks(rhythmTask);
    if(rhythmEnabled&&strokeSync)rhythmHandler.postDelayed(rhythmTask,Math.max(0,strokeNextBeat-now));
  }
  private final Runnable rhythmTask=new Runnable(){@Override public void run(){
    if(!rhythmEnabled)return;
    long now=android.os.SystemClock.uptimeMillis();
    if(strokeSync){
      if(strokeNextBeat==0){resetStrokeClock();return;}
      if(now<strokeNextBeat){rhythmHandler.postDelayed(this,Math.max(1,strokeNextBeat-now));return;}
      try{if(rhythmTone!=null)playSoftPulse();}catch(Exception ignored){}
      // Compute every target from the original anchor and fractional period.
      // Skip late cycles instead of accumulating rounded-interval timing errors.
      strokeCycleIndex++;
      double period=strokePeriodExactMs();
      long minCycle=Math.max(0,(long)Math.floor((now-strokeAnchorMs)/period-strokeOffsetPercent/100.0)+1);
      if(strokeCycleIndex<minCycle)strokeCycleIndex=minCycle;
      strokeNextBeat=strokeTargetMs(strokeCycleIndex);
      if(rhythmStatus!=null)rhythmStatus.setText(String.format(java.util.Locale.US,"Stroke Sync • %.2f cycles/sec • one cue per cycle",preciseStrokeBps>0?preciseStrokeBps:rhythmBpm/60.0));
      rhythmHandler.postDelayed(this,Math.max(1,strokeNextBeat-android.os.SystemClock.uptimeMillis()));
      return;
    }
    try{if(rhythmTone!=null)playSoftPulse();}catch(Exception ignored){}
    rhythmTick++;int bpm=rhythmBpm;
    if(rhythmPattern==1)bpm=Math.max(15,Math.min(600,rhythmBpm+(int)(30*Math.sin(rhythmTick/5.0))));
    if(rhythmPattern==2)bpm=Math.min(600,rhythmBpm+rhythmTick/4);
    if(rhythmStatus!=null)rhythmStatus.setText(String.format(java.util.Locale.US,"Rhythm guide • %.1f beats/sec • audible cue",bpm/60.0));
    rhythmHandler.postDelayed(this,60000L/Math.max(1,bpm));
  }};
  private final int bg = Color.rgb(16,13,24), panel = Color.rgb(34,25,45), accent = Color.rgb(187,115,213);
  private static class Track {
    MediaPlayer player; Uri uri; SeekBar volume; SeekBar speed; SeekBar progress; TextView progressLabel; boolean draggingProgress=false; CheckBox loop; TextView label; LinearLayout card; boolean muted=false, solo=false; int trimStartMs=0,trimEndMs=0,fadeInMs=0,fadeOutMs=0,startAtMs=0; boolean scheduledActive=false; TextView startLabel;
    Track(MediaPlayer p,Uri u){player=p;uri=u;}
  }
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    clipHandler.post(clipMonitor);
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    ScrollView scroll = new ScrollView(this);homeScroll=scroll;scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,30,24,24);scroll.addView(root);setContentView(scroll);
    TextView eyebrow=text("Y O U R   P R I V A T E   S O U N D   S T U D I O",13,true);eyebrow.setTextColor(accent);root.addView(eyebrow);
    TextView heading=text("Tallulah Audio Lab",30,true);heading.setTextColor(Color.WHITE);root.addView(heading);
    TextView sub=text("Layer audio and design your own soundscapes.",13,false);sub.setTextColor(0xffc6a6cf);root.addView(sub);
    LinearLayout audioStudio=collapsibleSection(root,"♫  Audio Studio",true);
    LinearLayout hismithGroup=collapsibleSection(root,"⚡  Hismith Machine & Live Sync",false);
    LinearLayout soundGenerators=collapsibleSection(root,"♫  Sound Generators",false);
    LinearLayout lightingGroup=collapsibleSection(root,"✦  Govee Lighting",false);
    LinearLayout syncGroup=collapsibleSection(root,"◉  Audio Sync",false);
    LinearLayout advancedGroup=collapsibleSection(root,"⚙  Advanced Settings",false);
    LinearLayout quickNav=new LinearLayout(this);quickNav.setOrientation(1);
    root.addView(quickNav,3);
    quickNav.addView(text("QUICK NAVIGATION",13,true));
    quickNav.addView(text("Tap a destination to open it and jump straight there.",12,false));
    LinearLayout navRow1=new LinearLayout(this);navRow1.setOrientation(0);quickNav.addView(navRow1);
    Button navAudio=button("♫ Audio");navRow1.addView(navAudio,new LinearLayout.LayoutParams(0,dp(54),1));
    Button navHismith=button("⚡ Hismith");navRow1.addView(navHismith,new LinearLayout.LayoutParams(0,dp(54),1));
    LinearLayout navRow2=new LinearLayout(this);navRow2.setOrientation(0);quickNav.addView(navRow2);
    Button navLights=button("✦ Lights");navRow2.addView(navLights,new LinearLayout.LayoutParams(0,dp(54),1));
    Button navSync=button("◉ Audio Sync");navRow2.addView(navSync,new LinearLayout.LayoutParams(0,dp(54),1));
    Button navAdvanced=button("⚙ Settings");quickNav.addView(navAdvanced,new LinearLayout.LayoutParams(-1,dp(48)));
    navAudio.setOnClickListener(v->jumpToSection(audioStudio));
    navHismith.setOnClickListener(v->jumpToSection(hismithGroup));
    navLights.setOnClickListener(v->jumpToSection(lightingGroup));
    navSync.setOnClickListener(v->jumpToSection(syncGroup));
    navAdvanced.setOnClickListener(v->jumpToSection(advancedGroup));
    Button homeLiveSync=button("⚡ START HISMITH + GOVEE LIVE SYNC");
    quickNav.addView(homeLiveSync,new LinearLayout.LayoutParams(-1,dp(58)));
    TextView homeSyncStatus=text("Connect Hismith and discover Govee lights once, then start here.",12,false);quickNav.addView(homeSyncStatus);
    homeLiveSync.setOnClickListener(v->{
      if(hismithLightSyncActive){
        ArrayList<String> targets=new ArrayList<>(hismithLightTargets);
        stopHismithLightSync();
        if(!targets.isEmpty())sendGoveeCommand(targets,"brightness",50,null);
        homeLiveSync.setText("⚡ START HISMITH + GOVEE LIVE SYNC");
        homeSyncStatus.setText("Sync stopped.");
        return;
      }
      if(!hismithMonitorActive||hismithReportedSetting<1){
        homeSyncStatus.setText("Connect HISMITH and start its Bluetooth monitor first.");
        jumpToSection(hismithGroup,rhythmSection,bluetoothDiagnostics);
        return;
      }
      if(hismithLightTargetsSource==null||hismithLightTargetsSource.isEmpty()){
        homeSyncStatus.setText("Find Govee lights first.");
        jumpToSection(lightingGroup);
        return;
      }
      startHismithGoveeSync();
      if(hismithLightSyncActive){
        homeLiveSync.setText("■ STOP HISMITH + GOVEE LIVE SYNC");
        homeSyncStatus.setText("Sync active • Emergency All On still overrides lighting.");
      }
    });
    LinearLayout masterSection=collapsibleSection(audioStudio,"Master transport",true);
    Button add=button("＋ Add audio");
    add.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,PICK_AUDIO);});
    LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);masterSection.addView(controls);
    Button play=button("▶ Play mix");controls.addView(play,new LinearLayout.LayoutParams(0,-2,1));
    Button pause=button("Ⅱ Pause");controls.addView(pause,new LinearLayout.LayoutParams(0,-2,1));
    Button reset=button("↺ Restart edited mix");controls.addView(reset,new LinearLayout.LayoutParams(0,-2,1));
    play.setOnClickListener(v->{if(tracks.isEmpty()){status.setText("Add an audio file first.");return;}if(!playing){playing=true;mixClockStartedAt=android.os.SystemClock.elapsedRealtime()-mixClockPositionMs;for(Track t:tracks)t.scheduledActive=false;status.setText("Playing mix with scheduled start times");}});
    pause.setOnClickListener(v->{mixClockPositionMs=mixPosition();playing=false;for(Track t:tracks){if(t.player.isPlaying())t.player.pause();t.scheduledActive=false;}status.setText("Paused");});
    reset.setOnClickListener(v->{playing=false;positionMix(0);status.setText("Reset to start");});
    TextView timelineTitle=text("Mix position",14,true);timelineTitle.setPadding(0,14,0,4);masterSection.addView(timelineTitle);
    masterTime=text("0:00 / 0:00",14,false);masterSection.addView(masterTime);
    masterSeek=new SeekBar(this);masterSeek.setMax(1);masterSection.addView(masterSeek);
    masterSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int value,boolean user){if(user){masterTime.setText(clock(value)+" / "+clock(getMixDuration()));}}
      public void onStartTrackingTouch(SeekBar bar){draggingMaster=true;}
      public void onStopTrackingTouch(SeekBar bar){int target=bar.getProgress();positionMix(target);draggingMaster=false;updateMasterTimeline();}
    });
    Button saveProject=button("💾 Save Project (instant)");masterSection.addView(saveProject);saveProject.setOnClickListener(v->saveProject());
    Button openProject=button("📂 My Projects");masterSection.addView(openProject);openProject.setOnClickListener(v->showProjects());
    Button saveMix=button("⬇ Export WAV audio");masterSection.addView(saveMix);
    saveMix.setOnClickListener(v->saveEditedAudio());
    status=text("Ready to import audio",14,false);status.setTextColor(0xffcbb5d5);status.setPadding(0,16,0,14);root.addView(status);
    LinearLayout audioSection=collapsibleSection(audioStudio,"Audio layers",false);
    audioSection.addView(add);
    Button video=button("▣ Extract audio from video");audioSection.addView(video);video.setOnClickListener(v->status.setText("Video extraction is not available in this build yet."));
    trackList=new LinearLayout(this);trackList.setOrientation(1);audioSection.addView(trackList);
    LinearLayout rhythmSection=collapsibleSection(hismithGroup,"Hismith rhythm guide",false);
    LinearLayout rhythmCard=new LinearLayout(this);rhythmCard.setOrientation(1);rhythmCard.setPadding(20,20,20,20);
    GradientDrawable rhythmBg=new GradientDrawable();rhythmBg.setColor(0xff1e1e32);rhythmBg.setCornerRadius(28);rhythmCard.setBackground(rhythmBg);rhythmSection.addView(rhythmCard);
    TextView rhythmHeading=text("HISMITH STROKE RHYTHM",18,true);rhythmHeading.setTextColor(0xffb497ff);rhythmCard.addView(rhythmHeading);
    TextView rhythmDesc=text("Audible timing guide only. Does not connect to or control a device.",13,false);rhythmCard.addView(rhythmDesc);
    LinearLayout bluetoothDiagnostics=collapsibleSection(rhythmSection,"Bluetooth Diagnostics • Read Only",false);
    bluetoothDiagnostics.addView(text("Scan for nearby BLE devices. Tap a result to inspect service UUIDs and properties. No speed or motor commands are sent. The Hismith app may need to disconnect first.",13,false));
    hismithBleStatus=text("Not scanned. Keep the wired motor stop accessible.",13,false);
    bluetoothDiagnostics.addView(hismithBleStatus);
    Button bleScanButton=button("Scan nearby Bluetooth devices");bluetoothDiagnostics.addView(bleScanButton);
    bleScanButton.setOnClickListener(v->startHismithScan());
    Button bleDisconnectButton=button("Disconnect Bluetooth diagnostic");bluetoothDiagnostics.addView(bleDisconnectButton);
    bleDisconnectButton.setOnClickListener(v->{stopHismithScan();closeHismithGatt();hismithBleStatus.setText("Bluetooth diagnostic disconnected.");});
    hismithBleResults=new LinearLayout(this);hismithBleResults.setOrientation(1);bluetoothDiagnostics.addView(hismithBleResults);
    hismithLiveStatus=text("Live monitor off. No notifications recorded.",13,false);bluetoothDiagnostics.addView(hismithLiveStatus);
    LinearLayout speedDashboard=collapsibleSection(bluetoothDiagnostics,"Live Speed Dashboard & Calibration",true);
    hismithDashboard=text("HISMITH LIVE SPEED\nWaiting for Bluetooth\nRhythm frequency: not calibrated",18,true);
    speedDashboard.addView(hismithDashboard);
    speedDashboard.addView(text("Calibrate only with a measured full-cycle rate (BPS) at the currently reported setting. The estimate assumes speed scales proportionally and may be inaccurate.",12,false));
    hismithMeasuredBpsInput=new EditText(this);
    hismithMeasuredBpsInput.setHint("Measured cycles per second (e.g. 1.5)");
    hismithMeasuredBpsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
    speedDashboard.addView(hismithMeasuredBpsInput);
    Button calibrateSpeed=button("Save calibration at current setting");speedDashboard.addView(calibrateSpeed);
    calibrateSpeed.setOnClickListener(v->{
      if(hismithReportedSetting<=0){Toast.makeText(this,"Connect and select a nonzero speed first.",Toast.LENGTH_SHORT).show();return;}
      try{
        double value=Double.parseDouble(hismithMeasuredBpsInput.getText().toString().trim());
        if(!Double.isFinite(value)||value<=0||value>20)throw new NumberFormatException();
        hismithCalibrationSetting=hismithReportedSetting;hismithCalibrationBps=value;
        renderHismithDashboard();
      }catch(NumberFormatException e){Toast.makeText(this,"Enter a valid measured BPS (0–20).",Toast.LENGTH_SHORT).show();}
    });
    Button clearCalibration=button("Clear calibration");speedDashboard.addView(clearCalibration);
    clearCalibration.setOnClickListener(v->{hismithCalibrationSetting=-1;hismithCalibrationBps=0;renderHismithDashboard();});
    hismithCandidateStatus=text("Candidate speed: waiting for AA 03 packets (unverified)",15,true);bluetoothDiagnostics.addView(hismithCandidateStatus);
    LinearLayout goveeSyncPanel=collapsibleSection(bluetoothDiagnostics,"Hismith → Govee Live Sync (Test)",false);
    Button jumpLiveSync=button("↳ Open Live Sync controls");
    hismithGroup.addView(jumpLiveSync,0);
    jumpLiveSync.setOnClickListener(v->jumpToSection(hismithGroup,rhythmSection,bluetoothDiagnostics,goveeSyncPanel));
    goveeSyncPanel.addView(text("Uses the measured speed-50 reference (2.45 BPS) with provisional proportional scaling. Pulses all discovered lights between 15% and 60% brightness. Bluetooth/Wi-Fi latency can cause drift; this is not motion-locked.",12,false));
    hismithLightSyncStatus=text("Live Sync OFF",13,true);goveeSyncPanel.addView(hismithLightSyncStatus);
    goveeSyncPanel.addView(text("Timing offset • -500 ms to +500 ms",12,false));
    SeekBar syncOffset=new SeekBar(this);syncOffset.setMax(100);syncOffset.setProgress(50);goveeSyncPanel.addView(syncOffset);
    TextView offsetReadout=text("Offset: 0 ms",12,false);goveeSyncPanel.addView(offsetReadout);
    syncOffset.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar b,int p,boolean user){hismithLightOffsetMs=(p-50)*10;offsetReadout.setText("Offset: "+hismithLightOffsetMs+" ms");}
      public void onStartTrackingTouch(SeekBar b){}
      public void onStopTrackingTouch(SeekBar b){}
    });
    Button syncStart=button("Start Hismith → Govee Sync");goveeSyncPanel.addView(syncStart);
    syncStart.setOnClickListener(v->startHismithGoveeSync());
    Button syncStop=button("Stop Hismith → Govee Sync");goveeSyncPanel.addView(syncStop);
    syncStop.setOnClickListener(v->{ArrayList<String> targets=new ArrayList<>(hismithLightTargets);stopHismithLightSync();if(!targets.isEmpty())sendGoveeCommand(targets,"brightness",50,null);});

    bluetoothDiagnostics.addView(text("Diagnostic only. Candidate byte is NOT verified motor speed. Compare with the original controller; keep the physical stop accessible.",12,false));
    hismithHistoryStatus=text("Recent BLE messages: none",12,false);bluetoothDiagnostics.addView(hismithHistoryStatus);
    Button clearBleHistory=button("Clear message history");bluetoothDiagnostics.addView(clearBleHistory);
    clearBleHistory.setOnClickListener(v->{hismithHistory.clear();renderHismithHistory();});

    Button bleLive=button("Start read-only live monitor");bluetoothDiagnostics.addView(bleLive);
    bleLive.setOnClickListener(v->startHismithMonitor());
    Button bleStopLive=button("Stop live monitor and disconnect");bluetoothDiagnostics.addView(bleStopLive);
    bleStopLive.setOnClickListener(v->{closeHismithGatt();hismithLiveStatus.setText("Monitor stopped and disconnected.");});


    rhythmStatus=text("Rhythm guide stopped",14,false);rhythmStatus.setPadding(0,14,0,8);rhythmCard.addView(rhythmStatus);
    Button rhythmToggle=button("▶ Start rhythm guide");rhythmCard.addView(rhythmToggle);
    rhythmToggle.setOnClickListener(v->{rhythmEnabled=!rhythmEnabled;rhythmHandler.removeCallbacks(rhythmTask);if(rhythmEnabled){rhythmTick=0;resetRhythmTone();if(strokeSync)resetStrokeClock();else rhythmHandler.post(rhythmTask);rhythmToggle.setText("■ Stop rhythm guide");}else{rhythmToggle.setText("▶ Start rhythm guide");rhythmStatus.setText("Rhythm guide stopped");if(rhythmTone!=null)try{rhythmTone.pause();}catch(Exception ignored){}}});
    TextView tempo=text("Beats per second • 0.7",18,true);rhythmCard.addView(tempo);
    TextView tempoHint=text("0.5–10.0 beats/sec • drag the large slider or tap − / +",13,false);rhythmCard.addView(tempoHint);
    LinearLayout tempoRow=new LinearLayout(this);tempoRow.setOrientation(LinearLayout.HORIZONTAL);tempoRow.setGravity(android.view.Gravity.CENTER_VERTICAL);rhythmCard.addView(tempoRow);
    Button slower=button("−");tempoRow.addView(slower,new LinearLayout.LayoutParams(dp(64),dp(64)));
    SeekBar tempoSlider=new SeekBar(this);tempoSlider.setMax(95);tempoSlider.setProgress(2);tempoSlider.setMinHeight(dp(64));tempoSlider.setPadding(dp(14),dp(12),dp(14),dp(12));
    tempoRow.addView(tempoSlider,new LinearLayout.LayoutParams(0,dp(72),1));
    Button faster=button("+");tempoRow.addView(faster,new LinearLayout.LayoutParams(dp(64),dp(64)));
    tempoSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar b,int p,boolean user){
        double beatsPerSecond=(p+5)/10.0;
        rhythmBpm=(int)Math.round(beatsPerSecond*60);
        tempo.setText(String.format(java.util.Locale.US,"Beats per second • %.1f",beatsPerSecond));
        if(user){preciseStrokeBps=0.0;}if(user&&rhythmEnabled){if(strokeSync)resetStrokeClock();else{rhythmHandler.removeCallbacks(rhythmTask);rhythmHandler.post(rhythmTask);}}
      }
      public void onStartTrackingTouch(SeekBar b){}
      public void onStopTrackingTouch(SeekBar b){}
    });
    slower.setOnClickListener(v->tempoSlider.setProgress(Math.max(0,tempoSlider.getProgress()-1)));
    faster.setOnClickListener(v->tempoSlider.setProgress(Math.min(tempoSlider.getMax(),tempoSlider.getProgress()+1)));
    CheckBox strokeMode=new CheckBox(this);strokeMode.setText("Stroke Sync • one beat per full back-and-forth cycle");strokeMode.setTextColor(Color.WHITE);rhythmCard.addView(strokeMode);
    TextView offsetLabel=text("Sync offset • 0% of stroke cycle",14,false);rhythmCard.addView(offsetLabel);
    LinearLayout offsetRow=new LinearLayout(this);offsetRow.setOrientation(LinearLayout.HORIZONTAL);offsetRow.setGravity(android.view.Gravity.CENTER_VERTICAL);rhythmCard.addView(offsetRow);
    Button offsetMinus=button("−");offsetRow.addView(offsetMinus,new LinearLayout.LayoutParams(dp(64),dp(64)));
    SeekBar offsetSlider=new SeekBar(this);offsetSlider.setMax(99);offsetSlider.setProgress(0);offsetSlider.setMinHeight(dp(64));offsetSlider.setPadding(dp(14),dp(12),dp(14),dp(12));offsetRow.addView(offsetSlider,new LinearLayout.LayoutParams(0,dp(72),1));
    Button offsetPlus=button("+");offsetRow.addView(offsetPlus,new LinearLayout.LayoutParams(dp(64),dp(64)));
    offsetMinus.setOnClickListener(v->offsetSlider.setProgress(Math.max(0,offsetSlider.getProgress()-1)));
    offsetPlus.setOnClickListener(v->offsetSlider.setProgress(Math.min(offsetSlider.getMax(),offsetSlider.getProgress()+1)));
    TextView syncHelp=text("Match BPS to complete strokes/sec. Tap Sync Now at a repeatable point in the motion, then adjust the offset using the slider or − / + buttons without restarting synchronization. Manual timing only; no device connection.",13,false);rhythmCard.addView(syncHelp);
    Button syncNow=button("◎ Sync Now");rhythmCard.addView(syncNow);
    strokeMode.setOnCheckedChangeListener((button,checked)->{
      strokeSync=checked;
      if(strokeSync){resetStrokeClock();}
      else{rhythmHandler.removeCallbacks(rhythmTask);if(rhythmEnabled)rhythmHandler.post(rhythmTask);}
    });
    offsetSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
        strokeOffsetPercent=progress;offsetLabel.setText("Sync offset • "+progress+"% of stroke cycle");
        if(strokeSync)rescheduleStrokeClock();
      }
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){}
    });
    syncNow.setOnClickListener(v->{if(!strokeSync){strokeMode.setChecked(true);}resetStrokeClock();rhythmStatus.setText("Sync reference reset • fine-tune offset with − / +");});
    TextView tapTitle=text("Tap to Match • one tap per complete stroke",16,true);rhythmCard.addView(tapTitle);
    TextView tapResult=text("Tap at the same point each cycle. Use 5–10 taps for a stable estimate.",13,false);rhythmCard.addView(tapResult);
    // Large circular touch target for tapping along with the machine.
    LinearLayout tapCenter=new LinearLayout(this);tapCenter.setGravity(android.view.Gravity.CENTER);tapCenter.setOrientation(LinearLayout.VERTICAL);rhythmCard.addView(tapCenter);
    Button tapMatch=button("TAP");
    GradientDrawable tapCircle=new GradientDrawable();
    tapCircle.setShape(GradientDrawable.OVAL);
    tapCircle.setColor(Color.rgb(155,104,222));
    tapCircle.setStroke(dp(3),Color.rgb(210,181,250));
    tapMatch.setBackground(tapCircle);
    tapMatch.setTextColor(Color.BLACK);tapMatch.setTextSize(25);tapMatch.setAllCaps(false);
    tapMatch.setPadding(0,0,0,0);
    tapCenter.addView(tapMatch,new LinearLayout.LayoutParams(dp(192),dp(192)));
    Button tapClear=button("Reset");
    tapClear.setTextSize(12);tapClear.setAllCaps(false);
    LinearLayout.LayoutParams resetParams=new LinearLayout.LayoutParams(dp(110),dp(44));
    resetParams.gravity=android.view.Gravity.CENTER_HORIZONTAL;
    resetParams.topMargin=dp(8);
    tapCenter.addView(tapClear,resetParams);
    final java.util.ArrayList<Long> tapTimes=new java.util.ArrayList<>();
    final int[] tapCount={0};
    tapClear.setOnClickListener(v->{
      tapTimes.clear();tapCount[0]=0;
      tapResult.setText("Ready • tap once per full back-and-forth stroke");
    });
    tapMatch.setOnClickListener(v->{
      long now=android.os.SystemClock.uptimeMillis();
      tapCount[0]++;
      // Long pauses are excluded from tempo measurement, not treated as a reset.
      if(!tapTimes.isEmpty()&&now-tapTimes.get(tapTimes.size()-1)>2500){
        tapTimes.clear();
      }
      tapTimes.add(now);
      // Keep a rolling window of 24 taps, while retaining the full session count.
      if(tapTimes.size()>24)tapTimes.remove(0);
      if(tapTimes.size()<3){
        tapResult.setText("Taps: "+tapCount[0]+" • keep tapping to calibrate");
        return;
      }
      java.util.ArrayList<Long> intervals=new java.util.ArrayList<>();
      for(int i=1;i<tapTimes.size();i++)intervals.add(tapTimes.get(i)-tapTimes.get(i-1));
      java.util.Collections.sort(intervals);
      long median=intervals.get(intervals.size()/2);
      if(intervals.size()%2==0)median=(median+intervals.get(intervals.size()/2-1))/2;
      if(median<=0)return;
      double measuredBps=1000.0/median;
      if(measuredBps<0.5||measuredBps>10.0){
        tapResult.setText(String.format(java.util.Locale.US,"Taps: %d • %.2f BPS outside range (0.5–10)",tapCount[0],measuredBps));
        return;
      }
      int position=Math.max(0,Math.min(95,(int)Math.round(measuredBps*10)-5));
      tempoSlider.setProgress(position);
      preciseStrokeBps=measuredBps;
      tempo.setText(String.format(java.util.Locale.US,"Beats per second • %.2f (auto)",measuredBps));
      if(!strokeMode.isChecked())strokeMode.setChecked(true);
      strokeAnchorMs=now;
      rescheduleStrokeClock();
      tapResult.setText(String.format(java.util.Locale.US,"AUTO • %.2f BPS • %d total taps • keep tapping to refine",measuredBps,tapCount[0]));
    });
    TextView soundLabel=text("♫  RHYTHM SOUNDS",18,true);
    soundLabel.setTextColor(0xffD9B6FF);
    soundLabel.setPadding(dp(16),dp(14),dp(16),dp(12));
    GradientDrawable soundHighlight=new GradientDrawable();
    soundHighlight.setColor(0xff49315D);soundHighlight.setCornerRadius(dp(16));
    soundHighlight.setStroke(dp(2),0xffB497FF);
    LinearLayout soundPanel=new LinearLayout(this);soundPanel.setOrientation(1);
    soundPanel.setPadding(dp(8),dp(4),dp(8),dp(12));soundPanel.setBackground(soundHighlight);
    LinearLayout.LayoutParams soundPanelParams=new LinearLayout.LayoutParams(-1,-2);
    soundPanelParams.setMargins(0,dp(12),0,dp(12));rhythmCard.addView(soundPanel,soundPanelParams);
    soundPanel.addView(soundLabel);
    Spinner soundPicker=new Spinner(this);
    String[] soundNames={"Original Soft Thump","Deep Bass","Heartbeat","Sharp Tap","Electronic Pulse"};
    ArrayAdapter<String> soundAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,soundNames){
      @Override public View getView(int position,View convertView,android.view.ViewGroup parent){
        View view=super.getView(position,convertView,parent);
        if(view instanceof TextView){((TextView)view).setTextColor(Color.WHITE);((TextView)view).setTextSize(17);view.setPadding(dp(12),dp(12),dp(12),dp(12));}
        return view;
      }
    };
    soundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    soundPicker.setAdapter(soundAdapter);
    soundPanel.addView(soundPicker);
    soundPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
        if(rhythmSound!=position){rhythmSound=position;resetRhythmTone();}
      }
      public void onNothingSelected(android.widget.AdapterView<?> parent){}
    });
    TextView loud=text("Guide volume • 75%",14,false);rhythmCard.addView(loud);
    SeekBar loudSlider=new SeekBar(this);loudSlider.setMax(100);loudSlider.setProgress(75);rhythmCard.addView(loudSlider);
    loudSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){rhythmVolume=p;loud.setText("Guide volume • "+p+"%");if(rhythmEnabled)resetRhythmTone();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    TextView punchLabel=text("Bass Punch • 100%",14,false);rhythmCard.addView(punchLabel);
    SeekBar punchSlider=new SeekBar(this);punchSlider.setMax(200);punchSlider.setProgress(100);rhythmCard.addView(punchSlider);
    punchSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){rhythmPunch=p;punchLabel.setText("Bass Punch • "+p+"%");if(rhythmEnabled)resetRhythmTone();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    Spinner patterns=new Spinner(this);String[] names={"Steady pulse","Wave • rise and fall","Build • gradual acceleration"};ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);patterns.setAdapter(adapter);rhythmCard.addView(patterns);
    patterns.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){rhythmPattern=position;rhythmTick=0;}public void onNothingSelected(android.widget.AdapterView<?> parent){}});

    LinearLayout presetContent=collapsibleSection(rhythmCard,"Saved rhythm presets",false);
    TextView presetTitle=text("Saved rhythm presets",16,true);presetContent.addView(presetTitle);
    EditText presetName=new EditText(this);presetName.setSingleLine(true);presetName.setHint("Preset name (e.g. Speed One)");presetName.setTextColor(Color.WHITE);presetName.setHintTextColor(0xffb8a9c8);presetContent.addView(presetName);
    Spinner presetPicker=new Spinner(this);presetContent.addView(presetPicker);
    final java.util.ArrayList<String> presetNames=new java.util.ArrayList<>();
    final ArrayAdapter<String> presetAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,presetNames);
    presetPicker.setAdapter(presetAdapter);
    final android.content.SharedPreferences rhythmPrefs=getSharedPreferences("rhythm_presets",MODE_PRIVATE);
    final Runnable refreshPresets=()->{
      String previous=presetPicker.getSelectedItem()==null?"":presetPicker.getSelectedItem().toString();
      presetNames.clear();presetNames.add("Select saved preset");
      try{
        org.json.JSONObject saved=new org.json.JSONObject(rhythmPrefs.getString("presets","{}"));
        java.util.Iterator<String> keys=saved.keys();
        while(keys.hasNext())presetNames.add(keys.next());
        java.util.Collections.sort(presetNames.subList(1,presetNames.size()));
      }catch(Exception ignored){}
      presetAdapter.notifyDataSetChanged();
      int selected=presetNames.indexOf(previous);presetPicker.setSelection(Math.max(0,selected));
    };
    refreshPresets.run();
    Button savePreset=button("Save rhythm preset");presetContent.addView(savePreset);
    Button loadPreset=button("Quick load selected preset");presetContent.addView(loadPreset);
    Button updatePreset=button("Update selected preset");presetContent.addView(updatePreset);
    Button renamePreset=button("Rename selected preset");presetContent.addView(renamePreset);
    Button deletePreset=button("Delete selected preset");presetContent.addView(deletePreset);
    TextView presetMessage=text("Presets stay on this phone until app data is cleared.",13,false);presetContent.addView(presetMessage);
    final java.util.function.Supplier<org.json.JSONObject> currentSettings=()->{
      org.json.JSONObject preset=new org.json.JSONObject();
      try{
        preset.put("bps",preciseStrokeBps>0?preciseStrokeBps:rhythmBpm/60.0);
        preset.put("sound",rhythmSound);preset.put("volume",rhythmVolume);
        preset.put("punch",rhythmPunch);preset.put("pattern",rhythmPattern);
        preset.put("offset",strokeOffsetPercent);preset.put("sync",strokeSync);
      }catch(Exception ignored){}
      return preset;
    };
    savePreset.setOnClickListener(v->{
      String name=presetName.getText().toString().trim();
      if(name.length()==0){presetMessage.setText("Enter a name first.");return;}
      if(name.length()>40){presetMessage.setText("Use a name under 40 characters.");return;}
      try{
        org.json.JSONObject saved=new org.json.JSONObject(rhythmPrefs.getString("presets","{}"));
        if(saved.has(name)){presetMessage.setText("That name already exists. Use Update selected preset to overwrite.");return;}
        saved.put(name,currentSettings.get());
        rhythmPrefs.edit().putString("presets",saved.toString()).apply();
        refreshPresets.run();presetPicker.setSelection(presetNames.indexOf(name));
        presetMessage.setText("Saved: "+name);
      }catch(Exception e){presetMessage.setText("Could not save preset.");}
    });
    updatePreset.setOnClickListener(v->{
      String name=presetPicker.getSelectedItem()==null?"":presetPicker.getSelectedItem().toString();
      if(name.equals("Select saved preset")){presetMessage.setText("Select a preset to update.");return;}
      new android.app.AlertDialog.Builder(this).setTitle("Update "+name+"?")
        .setMessage("Replace its saved settings with the current rhythm settings?")
        .setNegativeButton("Cancel",null).setPositiveButton("Update",(dialog,which)->{
          try{
            org.json.JSONObject saved=new org.json.JSONObject(rhythmPrefs.getString("presets","{}"));
            if(!saved.has(name)){presetMessage.setText("Preset no longer exists.");return;}
            saved.put(name,currentSettings.get());
            rhythmPrefs.edit().putString("presets",saved.toString()).apply();
            presetMessage.setText("Updated: "+name);
          }catch(Exception e){presetMessage.setText("Could not update preset.");}
        }).show();
    });
    renamePreset.setOnClickListener(v->{
      String oldName=presetPicker.getSelectedItem()==null?"":presetPicker.getSelectedItem().toString();
      String newName=presetName.getText().toString().trim();
      if(oldName.equals("Select saved preset")){presetMessage.setText("Select a preset to rename.");return;}
      if(newName.isEmpty()||newName.length()>40){presetMessage.setText("Enter a new name (1–40 characters).");return;}
      if(oldName.equals(newName)){presetMessage.setText("That is already its name.");return;}
      try{
        org.json.JSONObject saved=new org.json.JSONObject(rhythmPrefs.getString("presets","{}"));
        if(!saved.has(oldName)){presetMessage.setText("Preset no longer exists.");return;}
        if(saved.has(newName)){presetMessage.setText("That name is already used.");return;}
        new android.app.AlertDialog.Builder(this).setTitle("Rename preset?")
          .setMessage(oldName+" → "+newName)
          .setNegativeButton("Cancel",null).setPositiveButton("Rename",(dialog,which)->{
            try{
              org.json.JSONObject current=new org.json.JSONObject(rhythmPrefs.getString("presets","{}"));
              if(!current.has(oldName)||current.has(newName))return;
              Object settings=current.get(oldName);current.remove(oldName);current.put(newName,settings);
              rhythmPrefs.edit().putString("presets",current.toString()).apply();
              refreshPresets.run();presetPicker.setSelection(presetNames.indexOf(newName));
              presetMessage.setText("Renamed: "+newName);
            }catch(Exception e){presetMessage.setText("Could not rename preset.");}
          }).show();
      }catch(Exception e){presetMessage.setText("Could not rename preset.");}
    });
    deletePreset.setOnClickListener(v->{
      String name=presetPicker.getSelectedItem()==null?"":presetPicker.getSelectedItem().toString();
      if(name.equals("Select saved preset")){presetMessage.setText("Select a preset to delete.");return;}
      new android.app.AlertDialog.Builder(this).setTitle("Delete "+name+"?")
        .setMessage("This removes only the saved rhythm preset, not your audio files.")
        .setNegativeButton("Cancel",null).setPositiveButton("Delete",(dialog,which)->{
          try{
            org.json.JSONObject saved=new org.json.JSONObject(rhythmPrefs.getString("presets","{}"));
            saved.remove(name);rhythmPrefs.edit().putString("presets",saved.toString()).apply();
            refreshPresets.run();presetPicker.setSelection(0);
            presetMessage.setText("Deleted: "+name);
          }catch(Exception e){presetMessage.setText("Could not delete preset.");}
        }).show();
    });
    loadPreset.setOnClickListener(v->{
      String name=presetPicker.getSelectedItem()==null?"":presetPicker.getSelectedItem().toString();
      if(name.equals("Select saved preset")){presetMessage.setText("Choose a saved preset.");return;}
      try{
        org.json.JSONObject saved=new org.json.JSONObject(rhythmPrefs.getString("presets","{}"));
        org.json.JSONObject preset=saved.getJSONObject(name);
        double bps=Math.max(0.5,Math.min(10.0,preset.optDouble("bps",0.7)));
        tempoSlider.setProgress(Math.max(0,Math.min(95,(int)Math.round(bps*10)-5)));
        preciseStrokeBps=bps;
        tempo.setText(String.format(java.util.Locale.US,"Beats per second • %.2f (preset)",bps));
        soundPicker.setSelection(Math.max(0,Math.min(4,preset.optInt("sound",0))));
        loudSlider.setProgress(Math.max(0,Math.min(100,preset.optInt("volume",75))));
        punchSlider.setProgress(Math.max(0,Math.min(200,preset.optInt("punch",100))));
        patterns.setSelection(Math.max(0,Math.min(2,preset.optInt("pattern",0))));
        offsetSlider.setProgress(Math.max(0,Math.min(99,preset.optInt("offset",0))));
        strokeMode.setChecked(preset.optBoolean("sync",true));
        if(strokeSync)resetStrokeClock();
        else if(rhythmEnabled){rhythmHandler.removeCallbacks(rhythmTask);rhythmHandler.post(rhythmTask);}
        presetMessage.setText("Loaded: "+name+" • Tap Sync Now to align with the machine.");
      }catch(Exception e){presetMessage.setText("Could not load preset.");}
    });

    LinearLayout binauralSection=collapsibleSection(soundGenerators,"Binaural beat generator",false);
    LinearLayout binCard=new LinearLayout(this);binCard.setOrientation(1);binCard.setPadding(20,20,20,20);
    GradientDrawable binShape=new GradientDrawable();binShape.setColor(0xff1e1e32);binShape.setCornerRadius(24);binCard.setBackground(binShape);binauralSection.addView(binCard);
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
    LinearLayout dreamSection=collapsibleSection(soundGenerators,"Dream Sounds",false);
    LinearLayout dreamCard=new LinearLayout(this);dreamCard.setOrientation(1);dreamCard.setPadding(20,20,20,20);
    GradientDrawable dreamShape=new GradientDrawable();dreamShape.setColor(0xff1e1e32);dreamShape.setCornerRadius(24);dreamCard.setBackground(dreamShape);dreamSection.addView(dreamCard);
    Spinner dreamPicker=new Spinner(this);dreamPicker.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Dark Drone","Ocean Drift","Airy Whisper","Soft Night Pulse"}));dreamCard.addView(dreamPicker);
    dreamPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){atmosphereType=pos;}public void onNothingSelected(android.widget.AdapterView<?> p){}});
    TextView dreamVol=text("Dream volume • 20%",14,false);dreamCard.addView(dreamVol);
    SeekBar dreamSlider=new SeekBar(this);dreamSlider.setMax(100);dreamSlider.setProgress(20);dreamCard.addView(dreamSlider);
    dreamSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){atmosphereVolume=p;dreamVol.setText("Dream volume • "+p+"%");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
    Button dreamButton=button("▶ Start Dream Sounds");dreamCard.addView(dreamButton);
    dreamButton.setOnClickListener(v->{atmosphereOn=!atmosphereOn;startAtmosphereIfNeeded();dreamButton.setText(atmosphereOn?"■ Stop Dream Sounds":"▶ Start Dream Sounds");});
    dreamCard.addView(text("Synthesized sound textures. Use stereo headphones for binaural tones.",12,false));
    LinearLayout audioDetection=collapsibleSection(syncGroup,"Audio Detection & Sensitivity",false);
    audioDetection.addView(text("Test the live sound level from an imported track. This version does not change the lights.",13,false));
    audioLevelBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
    audioLevelBar.setMax(100);audioLevelBar.setProgress(0);audioLevelBar.setMinHeight(dp(45));audioDetection.addView(audioLevelBar);
    audioLevelStatus=text("Detection stopped",14,false);audioDetection.addView(audioLevelStatus);
    TextView sensitivityLabel=text("Sensitivity • -40%",14,true);audioDetection.addView(sensitivityLabel);
    LinearLayout sensitivityRow=new LinearLayout(this);sensitivityRow.setOrientation(LinearLayout.HORIZONTAL);
    audioDetection.addView(sensitivityRow);
    Button sensitivityMinus=button("−");sensitivityRow.addView(sensitivityMinus,new LinearLayout.LayoutParams(dp(58),dp(64)));
    SeekBar sensitivitySlider=new SeekBar(this);sensitivitySlider.setMax(180);sensitivitySlider.setProgress(40);
    sensitivityRow.addView(sensitivitySlider,new LinearLayout.LayoutParams(0,dp(64),1));
    Button sensitivityPlus=button("+");sensitivityRow.addView(sensitivityPlus,new LinearLayout.LayoutParams(dp(58),dp(64)));
    sensitivitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int progress,boolean user){
        audioSensitivity=progress+20;
        int adjustment=audioSensitivity-100;
        sensitivityLabel.setText("Sensitivity • "+(adjustment>0?"+":"")+adjustment+"%");
      }
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){}
    });
    sensitivityMinus.setOnClickListener(v->sensitivitySlider.setProgress(Math.max(0,sensitivitySlider.getProgress()-5)));
    sensitivityPlus.setOnClickListener(v->sensitivitySlider.setProgress(Math.min(180,sensitivitySlider.getProgress()+5)));
    Button levelStart=button("Start audio detection");audioDetection.addView(levelStart);
    Button levelStop=button("Stop audio detection");audioDetection.addView(levelStop);
    levelStart.setOnClickListener(v->{
      if(android.os.Build.VERSION.SDK_INT>=23&&checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},735);audioLevelStatus.setText("Allow audio permission, then tap Start again.");return;
      }
      levelTestActive=true;levelHandler.removeCallbacks(levelMonitor);levelHandler.post(levelMonitor);
      audioLevelStatus.setText("Waiting for an imported track to play…");
    });
    levelStop.setOnClickListener(v->{
      stopReactive();levelTestActive=false;levelHandler.removeCallbacks(levelMonitor);releaseLevelVisualizer();
      audioLevelBar.setProgress(0);audioLevelStatus.setText("Detection stopped");
    });
    LinearLayout lights=collapsibleSection(lightingGroup,"Lighting Controls",false);
    lights.addView(text("H801D • LAN discovery test. Keep phone on the same Wi-Fi as the lights.",13,false));
    TextView lightStatus=text("Not scanned yet",14,false);lights.addView(lightStatus);
    Spinner lightPicker=new Spinner(this);lights.addView(lightPicker);
    ArrayList<String> lightNames=new ArrayList<>();lightNames.add("All discovered lights");
    ArrayAdapter<String> lightAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,lightNames);
    lightPicker.setAdapter(lightAdapter);
    ArrayList<String> lightIps=new ArrayList<>();
    hismithLightTargetsSource=lightIps;
    LinearLayout reactiveSection=collapsibleSection(syncGroup,"Audio-Reactive Effects",false);
    reactiveSection.addView(text("Start Audio Detection first. All four discovered lights will react together, regardless of the light selector.",13,false));
    reactiveSection.addView(text("Reaction speed • Smooth                         Responsive",13,false));
    SeekBar reactiveSpeed=new SeekBar(this);reactiveSpeed.setMax(4);reactiveSpeed.setProgress(2);
    reactiveSpeed.setMinHeight(dp(68));reactiveSection.addView(reactiveSpeed);
    int[] reactiveIntervals={850,550,350,260,200};
    reactiveSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int p,boolean user){reactiveDelay=reactiveIntervals[p];}
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){}
    });
    reactiveDiagnostic=text("Audio level: -- • Requested brightness: --",14,false);
    reactiveSection.addView(reactiveDiagnostic);
    Button commandTest=button("TEST LIGHT COMMANDS • 10% / 100%");advancedGroup.addView(commandTest);
    commandTest.setOnClickListener(v->{
      if(lightIps.size()!=4){lightStatus.setText("Discover all four lights first.");return;}
      stopReactive();stopGoveeFlow();stopGoveeStrobe();
      ArrayList<String> targets=new ArrayList<>(lightIps);
      sendGoveeCommand(targets,"turn",1,null);
      sendGoveeCommand(targets,"brightness",10,null);
      reactiveDiagnostic.setText("Manual test: 10% → 100% → 10% (three seconds)");
      reactiveHandler.postDelayed(()->sendGoveeCommand(targets,"brightness",100,null),1100);
      reactiveHandler.postDelayed(()->sendGoveeCommand(targets,"brightness",10,null),2200);
      reactiveHandler.postDelayed(()->lightStatus.setText("Manual brightness test complete. Did all four lights change?"),3300);
    });
    reactiveSection.addView(text("Lighting response mode",14,true));
    Spinner responseMode=new Spinner(this);
    responseMode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Smooth Brightness (existing)","Bass Pulse (new)"}));
    reactiveSection.addView(responseMode);
    reactiveSection.addView(text("Bass pulse strength",13,false));
    SeekBar pulseStrength=new SeekBar(this);pulseStrength.setMax(80);pulseStrength.setProgress(55);
    pulseStrength.setMinHeight(dp(64));reactiveSection.addView(pulseStrength);
    pulseStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int p,boolean user){bassPulseStrength=p+20;}
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){}
    });
    Button reactiveStart=button("Start reactive brightness");reactiveSection.addView(reactiveStart);
    Button reactiveStop=button("■ Stop reactive lighting");reactiveSection.addView(reactiveStop);
    reactiveStart.setOnClickListener(v->{
      if(!levelTestActive){lightStatus.setText("Start Audio Detection first.");return;}
      if(lightIps.size()!=4){lightStatus.setText("Find all four lights before starting this test. Found: "+lightIps.size());return;}
      stopGoveeFlow();stopGoveeStrobe();stopReactive();
      bassPulseMode=responseMode.getSelectedItemPosition()==1;
      bassFloor=detectedBassLevel;bassPeak=detectedBassLevel+1;lastBassPulseMs=0;
      reactiveIps.addAll(lightIps);reactiveCommandCount=0;reactiveLastRequested=-1;reactiveSmoothed=0;reactiveBaseline=detectedRawLevel;reactivePeak=detectedRawLevel+5;reactiveActive=true;
      sendGoveeCommand(new ArrayList<>(reactiveIps),"turn",1,null);
      reactiveHandler.post(reactiveTick);
      lightStatus.setText((bassPulseMode?"Bass Pulse":"Reactive brightness")+" running on all four lights.");
    });
    reactiveStop.setOnClickListener(v->{
      ArrayList<String> previous=new ArrayList<>(reactiveIps);
      stopReactive();
      if(!previous.isEmpty())sendGoveeCommand(previous,"brightness",50,null);
      lightStatus.setText("Reactive brightness stopped. All four lights set to 50%.");
    });
    Button scanLights=button("Find Govee lights");lights.addView(scanLights);
    scanLights.setOnClickListener(v->{
      lightStatus.setText("Scanning LAN for Govee lights…");
      scanLights.setEnabled(false);
      new Thread(()->{
        java.util.LinkedHashMap<String,String> found=new java.util.LinkedHashMap<>();
        android.net.wifi.WifiManager.MulticastLock lock=null;
        java.net.DatagramSocket socket=null;
        try{
          android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
          if(wifi!=null){lock=wifi.createMulticastLock("tallulah-govee-discovery");lock.setReferenceCounted(false);lock.acquire();}
          socket=new java.net.DatagramSocket(null);socket.setReuseAddress(true);
          socket.bind(new java.net.InetSocketAddress(4002));socket.setSoTimeout(500);
          byte[] request="{\"msg\":{\"cmd\":\"scan\",\"data\":{\"account_topic\":\"reserve\"}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          java.net.DatagramSocket sender=new java.net.DatagramSocket();
          try{
            sender.send(new java.net.DatagramPacket(request,request.length,java.net.InetAddress.getByName("239.255.255.250"),4001));
          }finally{sender.close();}
          long end=android.os.SystemClock.elapsedRealtime()+4500;
          while(android.os.SystemClock.elapsedRealtime()<end){
            try{
              byte[] buffer=new byte[2048];java.net.DatagramPacket packet=new java.net.DatagramPacket(buffer,buffer.length);
              socket.receive(packet);
              JSONObject message=new JSONObject(new String(packet.getData(),0,packet.getLength(),java.nio.charset.StandardCharsets.UTF_8));
              JSONObject data=message.optJSONObject("msg");
              if(data==null||!"scan".equals(data.optString("cmd")))continue;
              JSONObject details=data.optJSONObject("data");if(details==null)continue;
              String ip=details.optString("ip",packet.getAddress().getHostAddress());
              String model=details.optString("sku",details.optString("model","Govee"));
              if(!ip.isEmpty()&&!found.containsKey(ip))found.put(ip,model+" • "+ip);
            }catch(java.net.SocketTimeoutException ignored){}
          }
        }catch(Exception ex){
          String error=ex.getClass().getSimpleName()+": "+ex.getMessage();
          runOnUiThread(()->lightStatus.setText("LAN scan failed: "+error));
        }finally{
          if(socket!=null)socket.close();
          if(lock!=null&&lock.isHeld())lock.release();
        }
        runOnUiThread(()->{
          lightIps.clear();lightNames.clear();lightNames.add("All discovered lights");
          for(java.util.Map.Entry<String,String> entry:found.entrySet()){lightIps.add(entry.getKey());lightNames.add(entry.getValue());}
          lightAdapter.notifyDataSetChanged();lightPicker.setSelection(0);
          lightStatus.setText(found.size()+" light(s) found • expected 4");
          scanLights.setEnabled(true);
        });
      }).start();
    });
    LinearLayout lightButtons=new LinearLayout(this);lightButtons.setOrientation(LinearLayout.HORIZONTAL);lights.addView(lightButtons);
    Button lightsOn=button("Lights ON");Button lightsOff=button("Lights OFF");
    lightButtons.addView(lightsOn,new LinearLayout.LayoutParams(0,dp(58),1));
    lightButtons.addView(lightsOff,new LinearLayout.LayoutParams(0,dp(58),1));
    lightsOn.setOnClickListener(v->sendGoveePower(lightIps,lightPicker.getSelectedItemPosition(),true,lightStatus));
    lightsOff.setOnClickListener(v->sendGoveePower(lightIps,lightPicker.getSelectedItemPosition(),false,lightStatus));
    Button emergencyAll=button("EMERGENCY • ALL ON • 1%");
    root.addView(emergencyAll,3);
    emergencyAll.setContentDescription("Emergency all lights on. Stop lighting effects and set warm white to one percent brightness.");
    lights.addView(text("Requests 1000K warm white at 1% on all discovered lights. Actual color temperature depends on H801D firmware limits.",12,false));
    emergencyAll.setOnClickListener(v->{
      if(lightIps.isEmpty()){lightStatus.setText("Discover your lights first.");return;}
      // Emergency override: disable every automatic lighting controller first.
      stopHismithLightSync();
      stopReactive();
      stopGoveeFlow();
      stopGoveeStrobe();
      sendGoveeEmergency(new ArrayList<>(lightIps),lightStatus);
    });
    TextView brightnessText=text("Brightness • 50%",14,true);lights.addView(brightnessText);
    SeekBar brightnessBar=new SeekBar(this);brightnessBar.setMax(99);brightnessBar.setProgress(49);brightnessBar.setMinHeight(dp(64));lights.addView(brightnessBar);
    brightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int progress,boolean user){
        int value=progress+1;brightnessText.setText("Brightness • "+value+"%");
        if(user)goveeStrobeBrightness=value;
      }
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){
        int value=bar.getProgress()+1;
        if(goveeStrobeActive){lightStatus.setText("Stop strobe before changing brightness.");return;}
        sendGoveeCommand(selectedGoveeTargets(lightIps,lightPicker.getSelectedItemPosition()),"brightness",value,lightStatus);
      }
    });
    TextView colorHeading=text("COLOR PRESETS",18,true);colorHeading.setTextColor(0xffD9B6FF);lights.addView(colorHeading);
    SeekBar saturationBar=new SeekBar(this);saturationBar.setMax(100);saturationBar.setProgress(100);saturationBar.setMinHeight(dp(68));
    TextView saturationText=text("Color intensity • 100%",13,false);lights.addView(saturationText);lights.addView(saturationBar);
    saturationBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int value,boolean user){saturationText.setText("Color intensity • "+value+"%");}
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){}
    });
    LinearLayout presetColors=new LinearLayout(this);
    presetColors.setOrientation(LinearLayout.HORIZONTAL);
    presetColors.setGravity(android.view.Gravity.CENTER_VERTICAL);
    presetColors.setPadding(0,dp(8),0,dp(8));
    lights.addView(presetColors);
    String[] colorNames={"Deep Red","Plum","Purple","Ocean Blue","Turquoise"};
    int[] colorValues={0xffAA0022,0xff76235D,0xff7722CC,0xff0033CC,0xff00B5AA};
    for(int n=0;n<colorNames.length;n++){
      final int rgb=colorValues[n];
      final String colorName=colorNames[n];
      View swatch=new View(this);
      GradientDrawable swatchBackground=new GradientDrawable();
      swatchBackground.setColor(rgb);
      swatchBackground.setCornerRadius(dp(9));
      swatchBackground.setStroke(dp(2),0xffD6B9E9);
      swatch.setBackground(swatchBackground);
      swatch.setContentDescription(colorName);
      swatch.setClickable(true);
      swatch.setFocusable(true);
      LinearLayout.LayoutParams swatchParams=new LinearLayout.LayoutParams(0,dp(54),1);
      swatchParams.setMargins(dp(3),0,dp(3),0);
      presetColors.addView(swatch,swatchParams);
      swatch.setOnClickListener(v->{
        float[] hsv=new float[3];Color.colorToHSV(rgb,hsv);
        int adjusted=Color.HSVToColor(new float[]{hsv[0],hsv[1]*saturationBar.getProgress()/100f,hsv[2]});
        if(goveeStrobeActive){lightStatus.setText("Stop strobe/pulse before changing color.");return;}
        stopGoveeFlow();
        sendGoveeColor(selectedGoveeTargets(lightIps,lightPicker.getSelectedItemPosition()),adjusted,lightStatus);
      });
    }
    LinearLayout flowSection=collapsibleSection(lights,"Smooth Color Flow",false);
    flowSection.addView(text("Fluid color changes • selected light or all four",13,false));
    Spinner flowMode=new Spinner(this);
    ArrayAdapter<String> flowAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Gothic Flow • Red / Plum / Purple","Ocean Flow • Blue / Turquoise"});
    flowMode.setAdapter(flowAdapter);flowSection.addView(flowMode);
    flowSection.addView(text("Transition speed • Slow                         Fast",13,false));
    SeekBar flowSpeed=new SeekBar(this);flowSpeed.setMax(6);flowSpeed.setProgress(3);flowSpeed.setMinHeight(dp(72));flowSection.addView(flowSpeed);
    int[] flowDelays={1400,950,600,350,240,170,120};
    flowSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int p,boolean user){goveeFlowStepMs=flowDelays[p];}
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){}
    });
    Button flowStart=button("▶ Start Color Flow");flowSection.addView(flowStart);
    Button flowStop=button("■ Stop Color Flow");flowSection.addView(flowStop);
    flowStart.setOnClickListener(v->{
      if(goveeStrobeActive){lightStatus.setText("Stop strobe before starting Color Flow.");return;}
      ArrayList<String> targets=selectedGoveeTargets(lightIps,lightPicker.getSelectedItemPosition());
      if(targets.isEmpty()){lightStatus.setText("Find Govee lights first.");return;}
      stopGoveeFlow();
      goveeFlowTargets=new ArrayList<>(targets);
      goveeFlowMode=flowMode.getSelectedItemPosition();goveeFlowStep=0;goveeFlowActive=true;
      goveeFlowTask=new Runnable(){public void run(){
        if(!goveeFlowActive)return;
        int[] palette=goveeFlowPalettes[Math.max(0,Math.min(1,goveeFlowMode))];
        // Blend between adjacent palette colors over eight incremental steps.
        int segment=(goveeFlowStep/8)%palette.length;
        float blend=(goveeFlowStep%8)/8f;
        int first=palette[segment],second=palette[(segment+1)%palette.length];
        int red=Math.round(Color.red(first)*(1-blend)+Color.red(second)*blend);
        int green=Math.round(Color.green(first)*(1-blend)+Color.green(second)*blend);
        int blue=Math.round(Color.blue(first)*(1-blend)+Color.blue(second)*blend);
        sendGoveeColor(new ArrayList<>(goveeFlowTargets),Color.rgb(red,green,blue),null);
        goveeFlowStep++;
        goveeFlowHandler.postDelayed(this,goveeFlowStepMs);
      }};
      goveeFlowHandler.post(goveeFlowTask);
      lightStatus.setText("Color Flow running • use Stop to hold current color.");
    });
    flowStop.setOnClickListener(v->{stopGoveeFlow();lightStatus.setText("Color Flow stopped • current color held.");});
    TextView strobeTitle=text("Strobe • current light color",16,true);lights.addView(strobeTitle);
    TextView strobeSpeed=text("Strobe speed • Slow                         Fast",13,false);lights.addView(strobeSpeed);
    SeekBar strobeSlider=new SeekBar(this);strobeSlider.setMax(3);strobeSlider.setProgress(1);strobeSlider.setMinHeight(dp(64));lights.addView(strobeSlider);
    int[] strobeIntervals={2000,1000,750,500};
    strobeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int p,boolean user){
        goveeStrobeIntervalMs=strobeIntervals[p];

      }
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){}
    });
    CheckBox pulseMode=new CheckBox(this);pulseMode.setText("Soft pulse (dim / bright instead of off / on)");pulseMode.setTextColor(Color.WHITE);lights.addView(pulseMode);
    Button strobeStart=button("Start strobe / pulse");lights.addView(strobeStart);
    Button strobeStop=button("■ STOP • STEADY LIGHT");strobeStop.setTextSize(19);
    strobeStop.setMinHeight(dp(78));lights.addView(strobeStop);
    strobeStart.setOnClickListener(v->{
      if(goveeStrobeActive){lightStatus.setText("Strobe is already running.");return;}
      stopGoveeFlow();
      ArrayList<String> targets=selectedGoveeTargets(lightIps,lightPicker.getSelectedItemPosition());
      if(targets.isEmpty()){lightStatus.setText("Discover the lights first.");return;}
      goveeStrobeTargets=new ArrayList<>(targets);
      goveeStrobeBrightness=brightnessBar.getProgress()+1;
      goveeStrobeActive=true;
      final boolean[] lit={true};
      goveeStrobeTask=new Runnable(){public void run(){
        if(!goveeStrobeActive)return;
        lit[0]=!lit[0];
        if(pulseMode.isChecked()){
          sendGoveeCommand(goveeStrobeTargets,"brightness",lit[0]?goveeStrobeBrightness:Math.max(1,goveeStrobeBrightness/5),null);
        }else{
          sendGoveeCommand(goveeStrobeTargets,"turn",lit[0]?1:0,null);
        }
        goveeStrobeHandler.postDelayed(this,Math.max(250,goveeStrobeIntervalMs/2));
      }};
      sendGoveeCommand(targets,"brightness",goveeStrobeBrightness,null);
      goveeStrobeHandler.postDelayed(goveeStrobeTask,Math.max(250,goveeStrobeIntervalMs/2));
      lightStatus.setText("Strobe running • tap Stop for steady light");
    });
    strobeStop.setOnClickListener(v->{
      ArrayList<String> targets=new ArrayList<>(goveeStrobeTargets);
      stopGoveeStrobe();
      if(!targets.isEmpty())sendGoveeCommand(targets,"brightness",goveeStrobeBrightness,null);
      lightStatus.setText("Effect stopped • lights set to steady ON");
    });
    lights.addView(text("Flashing lights may trigger seizures or migraines. Stop immediately if uncomfortable. No automatic color changes.",12,false));
    TextView foot=text("Govee LAN controls are in testing. Keep using Govee Home if discovery fails.",13,false);foot.setTextColor(0xffb7a5be);foot.setPadding(0,28,0,0);root.addView(foot);
  }
  private ArrayList<String> selectedGoveeTargets(ArrayList<String> ips,int selected){
    ArrayList<String> targets=new ArrayList<>();
    if(selected==0)targets.addAll(ips);
    else if(selected>0&&selected<=ips.size())targets.add(ips.get(selected-1));
    return targets;
  }
  private void sendGoveeCommand(ArrayList<String> targets,String command,int value,TextView label){
    if(targets.isEmpty()){if(label!=null)label.setText("Scan for lights first.");return;}
    new Thread(()->{
      int sent=0;
      try(java.net.DatagramSocket socket=new java.net.DatagramSocket()){
        JSONObject data=new JSONObject();data.put("value",value);
        JSONObject msg=new JSONObject();msg.put("cmd",command);msg.put("data",data);
        JSONObject packetJson=new JSONObject();packetJson.put("msg",msg);
        byte[] payload=packetJson.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for(String ip:targets){try{
          socket.send(new java.net.DatagramPacket(payload,payload.length,java.net.InetAddress.getByName(ip),4003));sent++;
        }catch(Exception ignored){}}
      }catch(Exception ignored){}
      final int count=sent;
      if(label!=null)runOnUiThread(()->label.setText("Sent "+command+" to "+count+"/"+targets.size()+" light(s)."));
    }).start();
  }
  private void sendGoveeEmergency(ArrayList<String> targets,TextView label){
    label.setText("Sending emergency all-on settings to "+targets.size()+" lights…");
    new Thread(()->{
      int sent=0;
      try(java.net.DatagramSocket socket=new java.net.DatagramSocket()){
        for(String ip:targets){
          try{
            JSONObject power=new JSONObject();power.put("value",1);
            JSONObject white=new JSONObject();
            JSONObject rgb=new JSONObject();rgb.put("r",0);rgb.put("g",0);rgb.put("b",0);
            white.put("color",rgb);white.put("colorTemInKelvin",1000);
            JSONObject dim=new JSONObject();dim.put("value",1);
            JSONObject[] data={power,white,dim};
            String[] cmds={"turn","colorwc","brightness"};
            for(int j=0;j<cmds.length;j++){
              JSONObject msg=new JSONObject();msg.put("cmd",cmds[j]);msg.put("data",data[j]);
              JSONObject packet=new JSONObject();packet.put("msg",msg);
              byte[] bytes=packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
              socket.send(new java.net.DatagramPacket(bytes,bytes.length,java.net.InetAddress.getByName(ip),4003));
              Thread.sleep(80);
            }
            sent++;
          }catch(Exception ignored){}
        }
      }catch(Exception ignored){}
      final int count=sent;
      runOnUiThread(()->label.setText("Emergency commands sent to "+count+"/"+targets.size()+" lights. Verify warmth and 1% brightness; 1000K may be unsupported."));
    }).start();
  }
  private void sendGoveeColor(ArrayList<String> targets,int rgb,TextView label){
    if(targets.isEmpty()){if(label!=null)label.setText("Find Govee lights first.");return;}
    new Thread(()->{
      int sent=0;
      try(java.net.DatagramSocket socket=new java.net.DatagramSocket()){
        JSONObject color=new JSONObject();
        color.put("r",Color.red(rgb));color.put("g",Color.green(rgb));color.put("b",Color.blue(rgb));
        JSONObject data=new JSONObject();data.put("color",color);data.put("colorTemInKelvin",0);
        JSONObject msg=new JSONObject();msg.put("cmd","colorwc");msg.put("data",data);
        JSONObject packetJson=new JSONObject();packetJson.put("msg",msg);
        byte[] payload=packetJson.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for(String ip:targets){
          try{socket.send(new java.net.DatagramPacket(payload,payload.length,java.net.InetAddress.getByName(ip),4003));sent++;}
          catch(Exception ignored){}
        }
      }catch(Exception ignored){}
      final int count=sent;
      if(label!=null)runOnUiThread(()->label.setText("Color sent to "+count+"/"+targets.size()+" light(s). Check the actual color."));
    }).start();
  }
  private void stopGoveeFlow(){
    goveeFlowActive=false;
    if(goveeFlowTask!=null)goveeFlowHandler.removeCallbacks(goveeFlowTask);
    goveeFlowTargets.clear();
  }
  private void stopGoveeStrobe(){
    goveeStrobeActive=false;
    if(goveeStrobeTask!=null)goveeStrobeHandler.removeCallbacks(goveeStrobeTask);
    if(!goveeStrobeTargets.isEmpty()){
      sendGoveeCommand(new ArrayList<>(goveeStrobeTargets),"turn",1,null);
      goveeStrobeTargets.clear();
    }
  }
  private void sendGoveePower(ArrayList<String> ips,int selected,boolean on,TextView label){
    ArrayList<String> targets=new ArrayList<>();
    if(selected==0)targets.addAll(ips);
    else if(selected>0&&selected<=ips.size())targets.add(ips.get(selected-1));
    if(targets.isEmpty()){label.setText("Scan for lights first.");return;}
    label.setText("Sending "+(on?"ON":"OFF")+" to "+targets.size()+" light(s)…");
    new Thread(()->{
      int sent=0;
      try(java.net.DatagramSocket socket=new java.net.DatagramSocket()){
        byte[] payload=("{\"msg\":{\"cmd\":\"turn\",\"data\":{\"value\":"+ (on?1:0) +"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for(String ip:targets){
          try{socket.send(new java.net.DatagramPacket(payload,payload.length,java.net.InetAddress.getByName(ip),4003));sent++;}
          catch(Exception ignored){}
        }
      }catch(Exception ignored){}
      final int count=sent;
      runOnUiThread(()->label.setText("Command sent to "+count+"/"+targets.size()+" light(s). Check whether lights responded."));
    }).start();
  }
  private ScrollView homeScroll;
  private final java.util.HashMap<LinearLayout,TextView> sectionHeaders=new java.util.HashMap<>();
  private final java.util.HashMap<LinearLayout,String> sectionTitles=new java.util.HashMap<>();
  private void jumpToSection(LinearLayout... path){
    for(LinearLayout content:path){
      TextView header=sectionHeaders.get(content);
      if(header!=null&&content.getVisibility()!=View.VISIBLE){
        content.setVisibility(View.VISIBLE);
        header.setText("▾  "+sectionTitles.get(content));
      }
    }
    if(path.length>0&&homeScroll!=null){
      TextView header=sectionHeaders.get(path[path.length-1]);
      if(header!=null)homeScroll.post(()->{
        int[] pos=new int[2],base=new int[2];
        header.getLocationOnScreen(pos);homeScroll.getLocationOnScreen(base);
        homeScroll.smoothScrollBy(0,pos[1]-base[1]-dp(18));
      });
    }
  }
  private LinearLayout collapsibleSection(LinearLayout parent,String title,boolean initiallyOpen){
    LinearLayout section=new LinearLayout(this);section.setOrientation(1);
    LinearLayout.LayoutParams sectionParams=new LinearLayout.LayoutParams(-1,-2);sectionParams.setMargins(0,14,0,0);parent.addView(section,sectionParams);
    TextView header=text((initiallyOpen?"▾  ":"▸  ")+title,20,true);
    header.setTextColor(Color.WHITE);header.setPadding(20,22,20,22);
    GradientDrawable background=new GradientDrawable();background.setColor(0xff342440);background.setCornerRadius(20);header.setBackground(background);
    section.addView(header);
    LinearLayout content=new LinearLayout(this);content.setOrientation(1);content.setPadding(0,10,0,4);
    section.addView(content);content.setVisibility(initiallyOpen?View.VISIBLE:View.GONE);
    sectionHeaders.put(content,header);sectionTitles.put(content,title);
    header.setOnClickListener(v->{boolean open=content.getVisibility()!=View.VISIBLE;content.setVisibility(open?View.VISIBLE:View.GONE);header.setText((open?"▾  ":"▸  ")+title);});
    return content;
  }
  private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
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
      TextView startLabel=text("Start at: 0:00",13,true);card.addView(startLabel);t.startLabel=startLabel;
      SeekBar startAt=new SeekBar(this);startAt.setMax(600000);startAt.setProgress(0);card.addView(startAt);
      startAt.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
        public void onProgressChanged(SeekBar b,int p,boolean user){if(user){t.startAtMs=p;startLabel.setText("Start at: "+clock(p));updateMasterTimeline();}}
        public void onStartTrackingTouch(SeekBar b){}
        public void onStopTrackingTouch(SeekBar b){if(playing)positionMix(mixPosition());}
      });
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
  private int getMixDuration(){int max=0;for(Track t:tracks)max=Math.max(max,t.startAtMs+t.trimEndMs-t.trimStartMs);return max;}
  private void updateMasterTimeline(){
    if(masterSeek==null||masterTime==null||draggingMaster)return;
    int duration=getMixDuration(),position=mixPosition();
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
        JSONObject item=new JSONObject();item.put("uri",t.uri.toString());item.put("volume",t.volume.getProgress());item.put("loop",t.loop.isChecked());item.put("speed",t.speed.getProgress());item.put("start",t.trimStartMs);item.put("end",t.trimEndMs);item.put("fadeIn",t.fadeInMs);item.put("fadeOut",t.fadeOutMs);item.put("startAt",t.startAtMs);item.put("mute",t.muted);item.put("solo",t.solo);snapshots.add(item);
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
        t.fadeInMs=item.optInt("fadeIn",0);t.fadeOutMs=item.optInt("fadeOut",0);t.startAtMs=item.optInt("startAt",0);if(t.startLabel!=null)t.startLabel.setText("Start at: "+clock(t.startAtMs));
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
    int rate=44100, samples=rate*180/1000;
    byte[] pcm=new byte[samples*2];
    double strength=Math.max(0.1,Math.min(2.0,rhythmPunch/100.0));
    for(int i=0;i<samples;i++){
      double t=i/(double)rate;
      double wave=0.0;
      switch(rhythmSound){
        case 1: { // Deep bass: descending low-frequency body.
          double envelope=Math.exp(-t*23.0)*Math.min(1.0,t/0.008);
          wave=Math.sin(2*Math.PI*(82*t-110*t*t))*envelope;
          break;
        }
        case 2: { // Heartbeat: two rounded impulses in each beat.
          double first=Math.exp(-Math.pow((t-0.025)/0.017,2));
          double second=0.72*Math.exp(-Math.pow((t-0.112)/0.019,2));
          wave=(first+second)*Math.sin(2*Math.PI*85*t);
          break;
        }
        case 3: { // Sharp tap: short, bright transient.
          double envelope=Math.exp(-t*85.0)*Math.min(1.0,t/0.002);
          wave=(0.65*Math.sin(2*Math.PI*460*t)+0.35*Math.sin(2*Math.PI*900*t))*envelope;
          break;
        }
        case 4: { // Electronic pulse: short synthetic square-like tone.
          double envelope=Math.exp(-t*37.0)*Math.min(1.0,t/0.003);
          wave=Math.tanh(3.0*Math.sin(2*Math.PI*(170*t+200*t*t)))*envelope;
          break;
        }
        default: { // Original rounded thump.
          double envelope=Math.pow(1.0-Math.min(1.0,t/0.14),3);
          double attack=Math.min(1.0,t/0.012);
          wave=Math.sin(2*Math.PI*(65*t+20*t*t))*envelope*attack;
        }
      }
      double limited=Math.tanh(wave*strength*1.8)/Math.tanh(1.8);
      short sample=(short)(Math.max(-1.0,Math.min(1.0,limited))*29000);
      pcm[i*2]=(byte)(sample&255);pcm[i*2+1]=(byte)((sample>>8)&255);
    }
    try{
      rhythmTone=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length).build();
      rhythmTone.write(pcm,0,pcm.length);
      rhythmTone.setVolume(Math.min(1f,rhythmVolume/100f));
    }catch(Exception e){rhythmTone=null;if(rhythmStatus!=null)rhythmStatus.setText("Pulse audio unavailable");}
  }
  private void playSoftPulse(){
    if(rhythmTone==null)return;
    try{rhythmTone.pause();rhythmTone.setPlaybackHeadPosition(0);rhythmTone.play();}catch(Exception ignored){}
  }
  @Override protected void onDestroy(){stopHismithLightSync();stopHismithScan();closeHismithGatt();stopReactive();levelTestActive=false;levelHandler.removeCallbacks(levelMonitor);releaseLevelVisualizer();clipHandler.removeCallbacks(clipMonitor);atmosphereOn=false;stereoOn=false;rhythmEnabled=false;rhythmHandler.removeCallbacks(rhythmTask);if(rhythmTone!=null){rhythmTone.release();rhythmTone=null;}for(Track t:tracks)t.player.release();tracks.clear();super.onDestroy();}
}
