package org.quicdoc;

import android.app.Activity;
import android.app.NotificationManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.EditText;
import android.widget.Toast;
import android.view.View.*;
import android.view.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.util.Log;
import android.net.http.AndroidHttpClient;
import android.widget.TextView.BufferType;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import org.json.*;

enum Tag {
    CLIENT_READY,
	UPDATE_SERVER,
	SYNC_DOC,
	SYNCED_DOC,
	GET_DOC,
	GOT_DOC;
}

public class QuICDocEdit extends Activity
{
    String doc = "Loading document.";
    String marty = "Loading document.";
    EditText edittext;
    String serverName = "roquette.dyndns.org:4242";
    private Handler uiHandler;
    private QuICClient client;
    boolean focused;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

	edittext = (EditText) findViewById(R.id.edittext);
	edittext.setText(doc,BufferType.EDITABLE);

	uiHandler = new UiHandler();

	client = new QuICClient(uiHandler);
	client.start();
    }

    @Override
    public void onPause() {
	super.onPause();
	focused = false;
    }

    @Override
    public void onResume() {
	super.onResume();
	focused = true;
    }

    private class UiHandler extends Handler {
	public void handleMessage (Message msg) {
	    switch (Tag.values()[msg.what]) {
		case CLIENT_READY:
		    Message.obtain(client.mHandler, Tag.GET_DOC.ordinal(), serverName).sendToTarget(); break;
		case GOT_DOC:
		    edittext.addTextChangedListener(new TextWatcher() {
			    public void onTextChanged(CharSequence s, int start, int before, int count) { }
			    public void afterTextChanged(Editable s) {
				marty = s.toString();
				Message.obtain(client.mHandler, Tag.UPDATE_SERVER.ordinal(), new DiffArray(doc, marty)).sendToTarget();
				doc = marty;
			    }
			    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			});
		    edittext.setText((String) msg.obj, BufferType.EDITABLE);
		    timer.start();
		    break;
		case SYNCED_DOC:
		    if (msg.arg1 > 0) {
			doc = marty = (String) msg.obj;
			edittext.setText(doc,BufferType.EDITABLE);
		    }
		    Log.i("quicdoc","synced");
		}
	}
    }

    Thread timer = new Thread() {	    
	    public void run () {
		while (focused) {
		    Log.i("quicdoc","syncing");
		    Message.obtain(client.mHandler, Tag.SYNC_DOC.ordinal(), doc).sendToTarget();
		    try {
			Thread.sleep(1000);
		    } catch (java.lang.InterruptedException e) {}
		}
	    }
	};
}


class QuICClient extends Thread {
    private Handler backHandler;
    public Handler mHandler;
    final AndroidHttpClient httpClient = AndroidHttpClient.newInstance("QuICDoc");
    String serverName;
    int myId;

    public QuICClient(Handler h) {
	backHandler = h;
    }
    
    public void run() {
	Looper.prepare();
	mHandler = new QuICClientHandler();
	Message.obtain(backHandler, Tag.CLIENT_READY.ordinal()).sendToTarget();
	Looper.loop();
    }

    private class QuICClientHandler extends Handler {
	public void handleMessage(Message msg) {
	    switch (Tag.values()[msg.what]) {
		case GET_DOC: serverName = (String) msg.obj; getDoc(); break;
		case UPDATE_SERVER: updateServer((DiffArray) msg.obj); break;
		case SYNC_DOC: syncDoc((String) msg.obj); break;
		}
	}
    }

    protected void getDoc() {
	HttpGet req = new HttpGet("http://" + serverName + "/getDoc");
	try {
	    Log.i("quicdoc", "sending request");
	    HttpResponse resp = httpClient.execute(req);
	    Log.i("quicdoc", "sent request");
		    
	    StatusLine status = resp.getStatusLine();
	    if (status.getStatusCode() != 200) {
		Log.d("quicdoc", "HTTP error, invalid server status code: " + resp.getStatusLine());
		return;
	    }
		
	    String json = convertStreamToString(resp.getEntity().getContent());
	    JSONArray a = (JSONArray) new JSONTokener(json).nextValue();
	    Message.obtain(backHandler, Tag.GOT_DOC.ordinal(), a.getString(0)).sendToTarget();
	    myId = a.getInt(1);
	} catch (IOException e) {
	    Log.d("quicdoc", "HTTP IO error: " +e);
	} catch (org.json.JSONException e) {
	    Log.d("quicdoc", "Got an unJSONable doc");
	}
    }

    void updateServer(DiffArray diffs) {
	JSONStringer putreq = new JSONStringer();

	try {
	    putreq.object()
		.key("uid")
		.value(myId)
		.key("diffs")
		.value(diffs)
		.endObject()
		.toString();
	} catch (org.json.JSONException e) {
	    Log.d("quicdoc", "could not json the diffs");
	    return;
	}
	
	HttpGet req = new HttpGet("http://" + serverName + "/putDiff?" + Uri.encode(putreq.toString()));
	Log.i("quicdoc", "updating server with "+putreq);

	try {
	    HttpResponse resp = httpClient.execute(req);
	
	    StatusLine status = resp.getStatusLine();
	    if (status.getStatusCode() != 200) {
		Log.d("quicdoc", "HTTP error, invalid server status code: " + resp.getStatusLine());
		return;
	    }
	    String answer = convertStreamToString(resp.getEntity().getContent());
	    if(!answer.equals("OK\n")) {
		Log.d("quicdoc", "Concurrency Error!");
		return;
	    }
	    Log.i("quicdoc", "returning");
	} catch (IOException e) { }
    }

    void syncDoc(String doc) {
	HttpGet req = new HttpGet("http://" + serverName + "/getDiff?" + myId);
		
	try {
	    HttpResponse resp = httpClient.execute(req);
		    
	    StatusLine status = resp.getStatusLine();
	    if (status.getStatusCode() != 200) {
		Log.d("quicdoc", "HTTP error, invalid server status code: " + resp.getStatusLine());
	    }
		    
	    String json = convertStreamToString(resp.getEntity().getContent());
	    JSONArray diffs = (JSONArray) new JSONTokener(json).nextValue();
	    Log.i("quicdoc","zizi");
	    int i;
	    for(i = 0; i < diffs.length(); i++) {
		JSONObject o = diffs.getJSONObject(i);
		Diff d;
		Log.i("quicdoc","caca");
		if (o.getString("type").equals("insert"))
		    d = new Diff(o.getInt("point"),
				 o.getString("content"));
		else {
		    d = new Diff(o.getInt("point"),
				 o.getInt("length"));
		}
		Log.i("quicdoc","quicdoc" + d);
		doc = d.applyDiff(doc);
	    }
	    Message.obtain(backHandler, Tag.SYNCED_DOC.ordinal(), i, 0, doc).sendToTarget();
	} catch (IOException e) {}
	catch (org.json.JSONException e) {
	    Log.d("quicdoc", "Got an ununJSONable diff array");
	}
    }

    private static String convertStreamToString(InputStream is) {
	/*
	 * To convert the InputStream to String we use the BufferedReader.readLine()
	 * method. We iterate until the BufferedReader return null which means
	 * there's no more data to read. Each line will appended to a StringBuilder
	 * and returned as String.
	 *
	 * (c) public domain: http://senior.ceng.metu.edu.tr/2009/praeda/2009/01/11/a-simple-restful-client-at-android/
	 */
	BufferedReader reader = new BufferedReader(new InputStreamReader(is));
	StringBuilder sb = new StringBuilder();
	
	String line = null;
	try {
	    while ((line = reader.readLine()) != null) {
		sb.append(line + "\n");
	    }
	} catch (IOException e) {
	    e.printStackTrace();
	} finally {
	    try {
		is.close();
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	}
	return sb.toString();
    }
}

enum DiffType { INSERT, DELETE }

class Diff extends JSONObject {
    DiffType type;
    String content;
    int point;
    int length;

    public Diff(int p, int l) {
	type = DiffType.DELETE; content = "";
	point = p;
	length = l;
	try {
	    put("type","delete");
	    put("point",p);
	    put("length",l);
	} catch (JSONException e) {}
    }

    public Diff(int p, String c) {
	type = DiffType.INSERT;
	content = c;
	point = p;
	length = c.length();
	try {
	    put("type","insert");
	    put("point",p);
	    put("content",c);
	} catch (JSONException e) {}
    }

    public String applyDiff(String s) {
        if(type == DiffType.INSERT) {
	    return s.substring(0,point) + content + s.substring(point, s.length());
        }
	// else it's a DELETE (stupid Java won't let me put it an else branch)
	return s.substring(0,point) + s.substring(point + length, s.length());
    }

    // Tells you if two diffs clash footprints
    boolean clashWith(Diff d) {
	int end1 = point, end2 = d.point;
        if(type == DiffType.DELETE)
	    end1 = point + length;
        if(d.type == DiffType.DELETE)
	    end2 = d.point + d.length;
        if((point >= d.point && point <= end2) || 
	   (end1 >= d.point && end1 <= end2))
	    return true;
        return false;
    }

    void updateAfter(Diff d) {
        if(d.type == DiffType.DELETE && point > d.point)
	    point = point - d.length;
        else if(d.type == DiffType.INSERT && point > d.point)
	    point += d.length;

	try {
	    remove("point");
	    put("point",point);
	} catch (JSONException e) {}
    }
}

class DiffArray extends JSONArray {
    public DiffArray(String oldStr, String newStr) {
        if (oldStr.equals(newStr)) return;

	int i,j;
        // i := the number of equal chars at the beginning of the strings
        for(i = 0;
	    i < oldStr.length() && i< newStr.length() &&
		oldStr.charAt(i) == newStr.charAt(i);
	    i++) {}
        // j := the number of equal chars at the end of the strings
        for(j = 0;
	    j< oldStr.length() - i && j < newStr.length() - i &&
		oldStr.charAt(oldStr.length()-j-1) == newStr.charAt(newStr.length()-j-1);
	    j++) {}
        if(i+j != oldStr.length()) {
	    // Some stuff was deleted from oldStr
	    put(new Diff(i, oldStr.length()-i-j));
        }
        if(i+j != newStr.length()) {
	    // Some stuff was added to newStr
	    put(new Diff(i, newStr.substring(i,newStr.length()-j)));
        }
    }

    public DiffArray(CharSequence s, int start, int before, int count) {
        if(before > 0) {
	    // Some stuff was deleted
	    put(new Diff(start, before));
        }
        if(count > 0) {
	    // Some stuff was added
	    put(new Diff(start, s.subSequence(start,start + count).toString()));
        }
    }
}
