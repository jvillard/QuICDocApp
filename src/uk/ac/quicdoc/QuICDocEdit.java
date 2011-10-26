package org.quicdoc;

import android.app.Activity;
import android.app.NotificationManager;
import android.os.IBinder;
import android.os.Bundle;
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
import android.text.TextUtils;
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

public class QuICDocEdit extends Activity
{
    int myID = -1;
    String doc = "j'aime les arcs en ciel\nvus de ma fenêtre";
    String marty = "j'aime les arcs en ciel\nvus de ma fenêtre";
    DefaultHttpClient httpClient = new DefaultHttpClient(); // TODO: do internet in a separate thread
    String servername = "roquette.dyndns.org:4242";

    private void getdoc() {
	Log.i("bite", "logging in");

	HttpGet req = new HttpGet("http://" + servername + "/getDoc");

	try {
	    HttpResponse resp = httpClient.execute(req);
	
	    StatusLine status = resp.getStatusLine();
	    if (status.getStatusCode() != 200) {
		Log.d("bite", "HTTP error, invalid server status code: " + resp.getStatusLine());
		return;
	    }

	    String json = convertStreamToString(resp.getEntity().getContent());
	    JSONArray a = (JSONArray) new JSONTokener(json).nextValue();
	    doc = marty = a.getString(0);
	    myID = a.getInt(1);
	    
	} catch (IOException e) {
	    Log.d("bite", "HTTP IO error");
	}
	catch (org.json.JSONException e) {
	    Log.d("bite", "Got an unJSONable doc");
	}

	Log.i("bite", "logged in");
    }

    boolean updateServer() {
	/**
	 * Sends our local changes to the server.
	 * Returns success value.
	 */
	
	Log.i("bite", "updating server...");
	if (marty.equals(doc)) return true;
	Log.i("bite", "...for real");

	DiffArray diffs = new DiffArray(doc, marty);
	JSONStringer putreq = new JSONStringer();

	try {
	    putreq.object()
		.key("uid")
		.value(myID)
		.key("diffs")
		.value(diffs)
		.endObject()
		.toString();
	} catch (org.json.JSONException e) {
	    Log.d("bite", "could not json the diffs");
	    return false;
	}
	
	HttpGet req = new HttpGet("http://" + servername + "/putDiff?" + Uri.encode(putreq.toString()));

	Log.i("bite", putreq.toString());

	try {
	    HttpResponse resp = httpClient.execute(req);
	
	    StatusLine status = resp.getStatusLine();
	    if (status.getStatusCode() != 200) {
		Log.d("bite", "HTTP error, invalid server status code: " + resp.getStatusLine());
		return false;
	    }

	    String answer = convertStreamToString(resp.getEntity().getContent());
	    if(!answer.equals("OK\n")) {
		Toast.makeText(QuICDocEdit.this, "Concurrency Error!",
			       Toast.LENGTH_SHORT).show();
		return false;
	    }
	    Log.i("bite", "server updated");
	    return true;
	} catch (IOException e) { return false; }
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

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

	getdoc();
	
	//this.doBindService();

	final EditText edittext = (EditText) findViewById(R.id.edittext);

	edittext.setText(doc,BufferType.EDITABLE);

	edittext.setOnKeyListener(new OnKeyListener() {
		public boolean onKey(View v, int keyCode, KeyEvent event) {
		    if (event.getAction() == KeyEvent.ACTION_DOWN) {
			// Perform action on any key press
			boolean event_handled = edittext.onKeyDown(keyCode, event);
			marty = edittext.getText().toString();
			if (updateServer())
			    doc = marty;
			return event_handled;
		    }
		    return false;
		}
	    });
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
	return s.substring(0,point) + s.substring(point + content.length(), s.length());
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
}
