package com.proxoid;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.Socket;

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.animation.LinearInterpolator;
import android.widget.Toast;

public class Proxoid extends PreferenceActivity implements OnSharedPreferenceChangeListener, ServiceConnection {

	private static final String TAG = "proxoid";
	
	protected static final String KEY_PREFS		= "proxoidv6";
	
	protected static final String KEY_ONOFF		= "onoff";
	protected static final String KEY_PORT		= "port";
	protected static final String KEY_USERAGENT	= "useragent";
	
	protected static final String USERAGENT_ASIS	= "asis";
	protected static final String USERAGENT_REPLACE	= "replace";
	protected static final String USERAGENT_REMOVE	= "remove";
	
	protected static final String COMMAND_STOP = "stop";
	protected static final String COMMAND_START = "start";
	protected static final String COMMAND_UPDATE = "update";
	
	private IProxoidControl proxoidControl = null;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
			
		getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
		addPreferencesFromResource(R.xml.prefs);
		
		// Initialiser d'apres les prefs actuelle
		updateLibelle(KEY_PORT);
		updateLibelle(KEY_USERAGENT);
		
		Intent svc = new Intent(this, ProxoidService.class);
		bindService(svc, this, Context.BIND_AUTO_CREATE);
		
		Toast.makeText(this, "Debuging: "+Settings.System.getString(getContentResolver(), Settings.System.DEBUG_APP), Toast.LENGTH_LONG);
	}
	
	
	private SharedPreferences getSharedPreferences() {
		return super.getSharedPreferences(KEY_PREFS, MODE_PRIVATE);
	} 
	
	public SharedPreferences getSharedPreferences(String name, int mode) {
		return getSharedPreferences();
	}

	private boolean recurse = false;
	@Override
	public void onSharedPreferenceChanged(SharedPreferences pref, String key) {

		if (!recurse) {
			// Notifier
			boolean notifyOk = false;
			try {
				notifyOk = proxoidControl==null || proxoidControl.update();
			} catch (Exception e) {
				Log.e(TAG, "", e);
			}
			if (!notifyOk) {
				if (KEY_ONOFF.equals(key) && pref.getBoolean(KEY_ONOFF, false)) {
					try {
						recurse = true;
						SharedPreferences sp = getSharedPreferences();
						Editor e = sp.edit();
						e.putBoolean(KEY_ONOFF, false);
						e.commit();
						Toast.makeText(this, "Error. Proxoid stopped.", Toast.LENGTH_SHORT).show();
					} finally {
						recurse=false;
					}
				}
			}
		}
		
		// Mettre a jour le libelle
		updateLibelle(key);
	}
	
	private void updateLibelle(String key) {
		if (KEY_ONOFF.equals(key)) {
			return;
		}
		String summary = null;
		String value = getSharedPreferences().getString(key, null);
		if (KEY_PORT.equals(key)) {
			summary = "Listening port. Current value : "+(value==null?"8080":value);
		} else
		if (KEY_USERAGENT.equals(key)) {
			summary = "User-Agent filtering. Current value : "+
			(	USERAGENT_ASIS.equals(value)?"No filtering":
				USERAGENT_REMOVE.equals(value)?"Remove":
				"Replace with dummy"
			);
		}
		if (summary!=null) {
			PreferenceScreen ps = getPreferenceScreen();
			if (ps!=null) {
				Preference p = ps.findPreference(key);
				if (p!=null) {
					p.setSummary(summary);
				} 
			}
		}
		
	}
	
	private boolean inflateOK = false;
	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (!inflateOK) {
	        MenuInflater inflater = getMenuInflater();
	        inflater.inflate(R.menu.menu, menu);
	        inflateOK=true;
		}
        return true;
    }
	
	private String readLine(InputStream is) throws Exception {
		StringBuilder sb = new StringBuilder();
		char c;
		while ( (c=(char)is.read())!='\n' && c!='\r' ) {
			sb.append(c);
		}
		if (c=='\r') {
			is.read(); // \n
		}
		
		return sb.toString();
	}
	
	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		if (item.getItemId()==R.id.menu_help) {
			AlertDialog d = new AlertDialog.Builder(this).create();
			d.setTitle(null);
			d.setMessage("Please, go to\nhttp://code.google.com/p/proxoid/\nfrom your computer.");
			d.setButton("Ok", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}
			});
			d.show();			
		} else
		if (item.getItemId()==R.id.menu_download) {
			Builder d = new AlertDialog.Builder(this);
			d.setTitle(null);
			d.setMessage("This will download proxoid-adb.zip and save it to your sdcard.\n(cf http://code.google.com/p/proxoid/ for more info)\nContinue ?");
			d.setNegativeButton("No", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}
			});
			d.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
					
					final AlertDialog d = new AlertDialog.Builder(Proxoid.this).create();
					d.setTitle(null);
					d.setMessage("Initialising ...");
					d.show();
					
					Thread t = new Thread(new Runnable() {
						private String message = null;
						private boolean cancel = false;
						public void run() {
							if (message!=null) {
								if (cancel) {
									d.dismiss();
									Toast.makeText(Proxoid.this, message, Toast.LENGTH_LONG).show();
								} else {
									d.setMessage(message);
								}
								return;
							}
							try {
								message = "connecting ...";
								Proxoid.this.runOnUiThread(this);

								Socket so = new Socket("www.baroukh.com", 80);
								InputStream is = so.getInputStream();
								OutputStream os = so.getOutputStream();
								
								os.write("GET /proxoid/proxoid-adb.zip HTTP/1.0\n".getBytes());
								os.write("Host: www.baroukh.com\n".getBytes());
								os.write("User-agent: Proxoid\n".getBytes());
								os.write("\n".getBytes());
								os.flush();

								String s = null;
								String statusLine = null;
								int contentLength = 0;
								while ( (s=readLine(is))!=null && s.length()>0) {
									if (statusLine==null) {
										statusLine = s;
										if (!s.endsWith("200 OK")) {
											throw new Exception("Telechargement ko. Statusline="+statusLine);
										}
									} else {
										if (s.startsWith("Content-Length")) {
											contentLength=Integer.parseInt(s.substring(s.indexOf(' ')+1));
										}
									}
								}
								if (contentLength==0) {
									throw new Exception("Telechargement ko. pas de content-length !");
								}
								
								FileOutputStream fos = new FileOutputStream("/sdcard/proxoid-adb.zip");
								byte[] b = new byte[500];
								int nb;
								int total = 0;
								int pourcent = 0;
								while ( (nb=is.read(b))>0 ) {
									fos.write(b, 0, nb);
									total+=nb;
									int p = 100 * total / contentLength;
									if (p!=pourcent) {
										pourcent = p;
										message = "downloading ... "+p+"%";
										Proxoid.this.runOnUiThread(this);
									}
								}
								fos.close();
								if (total!=contentLength) {
									Log.e(TAG, "Download problem : expected : "+contentLength+", received : "+total);
									message = "Error : size does not match. Download may be complete (expected : "+contentLength+", received : "+total+") ...";
									cancel=true;
									Proxoid.this.runOnUiThread(this);
								} else {								
									message = "proxoid-adb.zip download complete ...";
									cancel=true;
									Proxoid.this.runOnUiThread(this);
								}
							} catch (Throwable t) {
								Log.e(TAG, "", t);
								message="Sorry: error while downloading ...";
								cancel=true;
								Proxoid.this.runOnUiThread(this);
							}
						}
					});
					t.start();
				}
			});
			d.show();			
		}
		return super.onMenuItemSelected(featureId, item);
	}


	@Override
	public void onServiceConnected(ComponentName cn, IBinder binder) {
		proxoidControl=(IProxoidControl)binder;
		if (proxoidControl!=null) {
			try {
				proxoidControl.update();
			} catch (RemoteException e) {
				Log.e(TAG, "", e);
			}
		}
	}


	@Override
	public void onServiceDisconnected(ComponentName cn) {
		proxoidControl=null;
	}
	
	@Override
	protected void onDestroy() {
		unbindService(this);
		super.onDestroy();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode==4 && proxoidControl!=null && getSharedPreferences().getBoolean(KEY_ONOFF, false)) {
			// On ne quitte pas ...
			Intent i = new Intent(Intent.ACTION_MAIN);
			i.addCategory(Intent.CATEGORY_HOME);
			startActivity(i);
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}
	
}