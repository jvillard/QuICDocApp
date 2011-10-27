package org.quicdoc;

import android.widget.EditText;
import android.widget.TextView.BufferType;
import org.json.*;

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

    public EditText applyDiff(EditText t) {
	String s = t.getText().toString();
	int selStart = t.getSelectionStart();
	int selEnd = t.getSelectionEnd();

        if (type == DiffType.INSERT) {
	    s = s.substring(0,point) + content + s.substring(point);
	    if (point <= selStart) {
		selStart += length; selEnd += length;
	    } else if (point <= t.getSelectionEnd())
		selEnd = selStart;
        } else {
	    // else it's a DELETE
	    s = s.substring(0,point) + s.substring(point + length, s.length());
	    if (point <= selStart) {
		if (point + length <= selStart) {
		    selStart -= length; selEnd -= length;
		} else {
		    selStart = selEnd = point;
		}
	    } else if (point > selStart && point <= selEnd)
		selEnd = selStart;
	}
	t.setText(s, BufferType.EDITABLE);
	t.setSelection(selStart, selEnd);
	return t;
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
