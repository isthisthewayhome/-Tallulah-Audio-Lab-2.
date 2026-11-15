package com.tallulah.audiolab2;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.webkit.*;
public class MainActivity extends Activity {
 WebView web; android.webkit.ValueCallback<Uri[]> chooser; static final int REQUEST=42;
 @Override public void onCreate(Bundle b){super.onCreate(b);web=new WebView(this);setContentView(web);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(true);web.setWebViewClient(new WebViewClient());web.setWebChromeClient(new WebChromeClient(){@Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> cb,FileChooserParams p){chooser=cb;try{startActivityForResult(p.createIntent(),REQUEST);return true;}catch(Exception e){chooser=null;return false;}}});web.loadUrl("file:///android_asset/index.html");}
 @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==REQUEST&&chooser!=null){chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(c,d));chooser=null;}}
}
