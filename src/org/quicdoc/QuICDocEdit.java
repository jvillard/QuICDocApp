package org.quicdoc;

import android.app.Activity;
import android.net.Uri;
import android.net.http.AndroidHttpClient;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.*;
import android.widget.EditText;
import android.widget.TextView.BufferType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;

import org.json.*;

enum Tag {
    CLIENT_READY,
	UPDATE_SERVER,
	SYNC_DOC,
	SYNCED_DOC,
	GET_DOC,
	GOT_DOC,
	STOP;
}

public class QuICDocEdit extends Activity {
    String doc = "Loading document.";
    String marty = "Loading document.";
    EditText editText;
    String serverName = "roquette.dyndns.org:4242";
    private Handler uiHandler;
    private QuICClient client;
    private final DocWatcher docWatcher = new DocWatcher();
    boolean focused;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

	editText = (EditText) findViewById(R.id.edittext);
	editText.setText(doc,BufferType.EDITABLE);

	uiHandler = new UiHandler();

	client = new QuICClient(uiHandler);
    }

    @Override
    public void onPause() {
	super.onPause();
	focused = false;
	Message.obtain(client.mHandler, Tag.STOP.ordinal()).sendToTarget();
    }

    @Override
    public void onResume() {
	super.onResume();
	focused = true;
	client.start();
    }

    private class UiHandler extends Handler {
	public void handleMessage (Message msg) {
	    switch (Tag.values()[msg.what]) {
	    case CLIENT_READY:
		Log.i("quicdoc", "Client ready.");
		Message.obtain(client.mHandler, Tag.GET_DOC.ordinal(), serverName).sendToTarget(); break;
	    case GOT_DOC:
		Log.i("quicdoc", "Got document.");
		doc = marty = (String) msg.obj;
		editText.setText(doc, BufferType.EDITABLE);
		editText.addTextChangedListener(docWatcher);
		timer.start();
		break;
	    case SYNCED_DOC:
		editText.removeTextChangedListener(docWatcher);
		JSONArray diffs = (JSONArray) msg.obj; 
		int i;
		for (i = 0; i < diffs.length(); i++) {
		    try {
			JSONObject o = diffs.getJSONObject(i);
			Diff d;
			if (o.getString("type").equals("insert"))
			    d = new Diff(o.getInt("point"),
					 o.getString("content"));
			else {
			    d = new Diff(o.getInt("point"),
					 o.getInt("length"));
			}
			d.applyDiff(editText);
		    } catch (org.json.JSONException e) {
			Log.d("quicdoc", "Got an ununJSONable diff array");
		    }
		}
		doc = marty = editText.getText().toString();
		editText.addTextChangedListener(docWatcher);
		if (i > 0)
		    Log.i("quicdoc", "Applied modifs from server.");
	    }
	}
    }

    private class DocWatcher implements TextWatcher {
	public void onTextChanged(CharSequence s, int start, int before, int count) { }
	public void afterTextChanged(Editable s) {
	    marty = s.toString();
	    Message.obtain(client.mHandler, Tag.UPDATE_SERVER.ordinal(), new DiffArray(doc, marty)).sendToTarget();
	    doc = marty;
	}
	public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
    }


    Thread timer = new Thread() {	    
	    public void run () {
		while (focused) {
		    Message.obtain(client.mHandler, Tag.SYNC_DOC.ordinal(), doc).sendToTarget();
		    try {
			Thread.sleep(1000);
		    } catch (java.lang.InterruptedException e) { }
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

    @Override
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
	    case STOP: Looper.myLooper().quit(); httpClient.close();
	    }
	}
    }

    protected void getDoc() {
	HttpGet req = new HttpGet("http://" + serverName + "/getDoc");
	try {
	    HttpResponse resp = httpClient.execute(req);
		    
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
	if (diffs.length() == 0) return;

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
	    Message.obtain(backHandler, Tag.SYNCED_DOC.ordinal(), diffs).sendToTarget();
	} catch (IOException e) {
	} catch (org.json.JSONException e) {
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
