package org.sipdroid.sipua.ui;

/*
 * Copyright (C) 2009 The Sipdroid Open Source Project
 * 
 * This file is part of Sipdroid (http://www.sipdroid.org)
 * 
 * Sipdroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.sipdroid.media.RtpStreamReceiver;
import org.sipdroid.sipua.UserAgent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Contacts;
import android.provider.Contacts.People;
import android.provider.Contacts.Phones;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

public class Caller extends BroadcastReceiver {

		private static final int REDIAL_MINIMUM_INTERVAL = 3000;
		static long noexclude;
		String last_number;
		long last_time;
		
		@Override
		public void onReceive(final Context context, Intent intent) {
	        String intentAction = intent.getAction();
	        String number = getResultData();
	        Boolean force = false;
	        
	        if (intentAction.equals(Intent.ACTION_NEW_OUTGOING_CALL) && number != null)
	        {
        		if (!Sipdroid.release)
        			Log.i("SipUA:","outgoing call");
        		
        		// is sipdroid enabled?
        		if (!Sipdroid.on(context))
        			return;
        		
    			boolean sip_type = !PreferenceManager.getDefaultSharedPreferences(context).getString(Settings.PREF_PREF, Settings.DEFAULT_PREF).equals(Settings.VAL_PREF_PSTN);
    	        boolean ask = PreferenceManager.getDefaultSharedPreferences(context).getString(Settings.PREF_PREF, Settings.DEFAULT_PREF).equals(Settings.VAL_PREF_ASK);
    	        
      	        if (Receiver.call_state != UserAgent.UA_STATE_IDLE && RtpStreamReceiver.isBluetoothAvailable()) {
       	        	setResultData(null);
       	        	switch (Receiver.call_state) {
    	        	case UserAgent.UA_STATE_INCOMING_CALL:
    	        		Receiver.engine(context).answercall();
    	        		if (RtpStreamReceiver.bluetoothmode)
    	        			break;
    	        	default:
    	        		if (RtpStreamReceiver.bluetoothmode)
    	        			Receiver.engine(context).rejectcall();
    	        		else
    	        			Receiver.engine(context).togglebluetooth();
    	        		break;	
       	        	}
       	        	return;
      	        }
      	        
      	        // Don't redial without required interval between attempts
    	        if (last_number != null && last_number.equals(number) && (SystemClock.elapsedRealtime()-last_time) < REDIAL_MINIMUM_INTERVAL) {
    	        	setResultData(null);
    	        	return;
    	        }
      	        last_time = SystemClock.elapsedRealtime();
    	        last_number = number;
    	        
    	        // Is the user over-riding the default network choice?
 				if (number.endsWith("+")) 
    			{
    				sip_type = !sip_type;
    				number = number.substring(0,number.length()-1);
    				force = true;
    			}
 				
				if (SystemClock.elapsedRealtime() < noexclude + 10000) {
					noexclude = 0;
					force = true;
				}
				
				// Look for numbers that are excluded from SIP routing
				// e.g. user can exclude SIP routing for calls
				// to PSTN voicemail number
				if (sip_type && !force) {
	    			String sExPat = PreferenceManager.getDefaultSharedPreferences(context).getString(Settings.PREF_EXCLUDEPAT, Settings.DEFAULT_EXCLUDEPAT); 
	   				boolean bExNums = false;
					boolean bExTypes = false;
					if (sExPat.length() > 0) 
					{					
						Vector<String> vExPats = getTokens(sExPat, ",");
						Vector<String> vPatNums = new Vector<String>();
						Vector<Integer> vTypesCode = new Vector<Integer>();					
				    	for(int i = 0; i < vExPats.size(); i++)
			            {
				    		if (vExPats.get(i).startsWith("h") || vExPats.get(i).startsWith("H"))
			        			vTypesCode.add(Integer.valueOf(People.Phones.TYPE_HOME));
				    		else if (vExPats.get(i).startsWith("m") || vExPats.get(i).startsWith("M"))
			        			vTypesCode.add(Integer.valueOf(People.Phones.TYPE_MOBILE));
				    		else if (vExPats.get(i).startsWith("w") || vExPats.get(i).startsWith("W"))
			        			vTypesCode.add(Integer.valueOf(People.Phones.TYPE_WORK));
				    		else 
				    			vPatNums.add(vExPats.get(i));     
			            }
						if(vTypesCode.size() > 0)
							bExTypes = isExcludedType(vTypesCode, number, context);
						if(vPatNums.size() > 0)
							bExNums = isExcludedNum(vPatNums, number);   					
					}	
					if (bExTypes || bExNums)
						sip_type = false;
				}

    			if (!sip_type) {
    				// PSTN call - let the normal handler dial it
    				setResultData(number);
    			} else {
    				// SIP call - start the dial process
	        		if (number != null && !intent.getBooleanExtra("android.phone.extra.ALREADY_CALLED",false)) {

	        		    	SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
		        			
		        		    // Migrate the "prefix" option.
		        			migratePrefixOption(sp);
	        		    	
	        		    	// Search & replace.
	    				String search = sp.getString(Settings.PREF_SEARCH, Settings.DEFAULT_SEARCH);
	    				String callthru_number = searchReplaceNumber(search, number);
	    				String callthru_prefix;
	    				
	    				// if "par" is true, get all numbers from the contact, concatenate with "&"
	    				if (!ask && !force && PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Settings.PREF_PAR, Settings.DEFAULT_PAR)) {
							number = concatenateNumbers(context, number, callthru_number, search);
	    				} else
	    					number = callthru_number;
						
						if (PreferenceManager.getDefaultSharedPreferences(context).getString(Settings.PREF_PREF, Settings.DEFAULT_PREF).equals(Settings.VAL_PREF_SIPONLY))
							force = true;
	    				if (!ask && Receiver.engine(context).call(number,force))
	    					setResultData(null);
	    				else if (!ask && PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Settings.PREF_CALLTHRU, Settings.DEFAULT_CALLTHRU) &&
	    						(callthru_prefix = PreferenceManager.getDefaultSharedPreferences(context).getString(Settings.PREF_CALLTHRU2, Settings.DEFAULT_CALLTHRU2)).length() > 0) {
	    					// Call failed - PSTN fall back
	    					callthru_number = (callthru_prefix+","+callthru_number+"#").replaceAll(",", ",p");
	    					setResultData(callthru_number);
	    				} else if (ask || force) {
	    					// Try the call another way?
	    					setResultData(null);
	    					final String n = number;
	    			        (new Thread() {
	    						public void run() {
			    					try {
										Thread.sleep(200);
									} catch (InterruptedException e) {
									}
			    			        Intent intent = new Intent(Intent.ACTION_CALL,
			    			                Uri.fromParts("sipdroid", Uri.decode(n), null));
			    			        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			    			        context.startActivity(intent);					
	    						}
	    			        }).start();  
	    				}
	        		}
	            }
	        }
	    }
		
		// TODO Remove this code in a future release.
		private void migratePrefixOption(SharedPreferences sp) {
		    if (sp.contains("prefix")) {
		        String prefix = sp.getString(Settings.PREF_PREFIX, Settings.DEFAULT_PREFIX);
		        Editor editor = sp.edit();
		        if (!prefix.trim().equals("")) {
		        	editor.putString(Settings.PREF_SEARCH, "(.*)," + prefix + "\\1");
		        }
		        editor.remove(Settings.PREF_PREFIX);
		        editor.commit();
		    }
		}
		
		private String concatenateNumbers(Context context, String _number, String callthru_number, String search) {
			String number = _number;
			/*	    					String orig = intent.getStringExtra("android.phone.extra.ORIGINAL_URI");	
				if (orig.lastIndexOf("/phones") >= 0) 
			{
					orig = orig.substring(0,orig.lastIndexOf("/phones")+7);
				Uri contactRef = Uri.parse(orig);
			 */
			Uri contactRef = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL, number);
			final String[] PHONES_PROJECTION = new String[] {
					People.Phones.NUMBER, // 0
					People.Phones.TYPE, // 1
			};
			Cursor phonesCursor = context.getContentResolver().query(contactRef, PHONES_PROJECTION, null, null,
					Phones.ISPRIMARY + " DESC");
			if (phonesCursor != null) {	        			        	
				number = "";
				while (phonesCursor.moveToNext()) {
					final int type = phonesCursor.getInt(1);
					String n = phonesCursor.getString(0);
					if (TextUtils.isEmpty(n))
						continue;
					if (type == Phones.TYPE_MOBILE || type == Phones.TYPE_HOME || type == Phones.TYPE_WORK) {
						if (!number.equals(""))
							number = number + "&";
						n = PhoneNumberUtils.stripSeparators(n);
						number = number + searchReplaceNumber(search, n);
					}
				}
				phonesCursor.close();
				if (number.equals(""))
					number = callthru_number;
			} else
				number = callthru_number;
			//			}			
			return number;
		}
		
		private String searchReplaceNumber(String pattern, String number) {
		    // Comma should be safe as separator.
		    String[] split = pattern.split(",");
		    // We need exactly 2 parts: search and replace. Otherwise
		    // we just return the current number.
		    if (split.length != 2)
			return number;

		    String modNumber = split[1];
		    
		    try {
			// Compiles the regular expression. This could be done
			// when the user modify the pattern... TODO Optimize
			// this, only compile once.
			Pattern p = Pattern.compile(split[0]);
    		    	Matcher m = p.matcher(number);
    		    	// Main loop of the function.
    		    	if (m.matches()) {
    		    	    for (int i = 0; i < m.groupCount() + 1; i++) {
    		    		String r = m.group(i);
    		    		if (r != null) {
    		    		    modNumber = modNumber.replace("\\" + i, r);
    		    		}
    		    	    }
    		    	}
    		    	// If the modified number is the same as the replacement
    		    	// value, we guess that the user typed a bad replacement
    		    	// value and we use the original number.
    		    	if (modNumber.equals(split[1])) {
    		    	    modNumber = number;
    		    	}
		    } catch (PatternSyntaxException e) {
			// Wrong pattern syntax. Give back the original number.
			modNumber = number;
		    }
		    
		    // Returns the modified number.
		    return modNumber;
		}
	    
	    Vector<String> getTokens(String sInput, String sDelimiter)
	    {
	    	Vector<String> vTokens = new Vector<String>();				
			int iStartIndex = 0;				
			final int iEndIndex = sInput.lastIndexOf(sDelimiter);
			for (; iStartIndex < iEndIndex; iStartIndex++) 
			{
				int iNextIndex = sInput.indexOf(sDelimiter, iStartIndex);
				String sPattern = sInput.substring(iStartIndex, iNextIndex).trim();
				vTokens.add(sPattern);
				iStartIndex = iNextIndex; 
			}
			if(iStartIndex < sInput.length())
				vTokens.add(sInput.substring(iStartIndex, sInput.length()).trim());
		
			return vTokens;
	    }
	    
	    boolean isExcludedNum(Vector<String> vExNums, String sNumber)
	    {
			for (int i = 0; i < vExNums.size(); i++) 
			{
				Pattern p = null;
				Matcher m = null;
				try
				{					
					p = Pattern.compile(vExNums.get(i));
					m = p.matcher(sNumber);	
				}
				catch(PatternSyntaxException pse)
				{
		           return false;    
				}  
				if(m != null && m.find())
					return true;			
			}    		
			return false;
	    }
	    
	    boolean isExcludedType(Vector<Integer> vExTypesCode, String sNumber, Context oContext)
	    {
	    	Uri contactRef = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL, sNumber);
	    	final String[] PHONES_PROJECTION = new String[] 
		    {
		        People.Phones.NUMBER, // 0
		        People.Phones.TYPE, // 1
		    };
	        Cursor phonesCursor = oContext.getContentResolver().query(contactRef, PHONES_PROJECTION, null, null,
	                null);
			if (phonesCursor != null) 
	        {	        			
 	            while (phonesCursor.moveToNext()) 
	            { 			            	
	                final int type = phonesCursor.getInt(1);	              
	                if(vExTypesCode.contains(Integer.valueOf(type)))
	                	return true;	    
	            }
	            phonesCursor.close();
	        }
			return false;
	    }   
	    
}
