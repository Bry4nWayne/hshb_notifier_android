/*
 * Hackerspace Bremen Android App - An Open-Space-Notifier for Android
 * 
 * Copyright (C) 2012 Steve Liedtke <sliedtke57@gmail.com>
 * 
 * This program is free software; you can redistribute it and/or modify it under the terms of the 
 * GNU General Public License as published by the Free Software Foundation; either version 3 of 
 * the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU General Public License for more details.
 * 
 * You can find a copy of the GNU General Public License on http://www.gnu.org/licenses/gpl.html.
 * 
 * Contributors:
 *     Steve Liedtke <sliedtke57@gmail.com>
 *     Matthias Friedrich <mtthsfrdrch@gmail.com>
 */
package de.hackerspacebremen.push;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Date;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager.NameNotFoundException;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.nokia.push.PushBaseIntentService;

import de.greenrobot.event.EventBus;
import de.hackerspacebremen.R;
import de.hackerspacebremen.StartActivity;
import de.hackerspacebremen.common.Constants;
import de.hackerspacebremen.communication.HackerspaceComm;
import de.hackerspacebremen.event.DataEvent;
import de.hackerspacebremen.event.StatusChanged;
import de.hackerspacebremen.format.SpeakingDateFormat;
import de.hackerspacebremen.valueobjects.SpaceData;
import de.hackerspacebremen.valueobjects.parser.SpaceDataJsonParser;
import de.hackerspacebremen.widgets.HackerspaceWidgetProvider;

public class PushIntentService extends PushBaseIntentService {

	private static String uniqueID = null;
	private static final String PREF_UNIQUE_ID = "hshb-android-app";
	
	public PushIntentService(){
	    super(Constants.SENDER_ID);
	}
	
	protected PushIntentService(final String senderId) {
		super(senderId);
	}

	

	@Override
	protected void onRegistered(final Context context, final String regId) {
		Log.i("Nokia Push", "Received registration ID");
		
		final String deviceId = this.getDeviceId(context);
		final RegisterCommunication comm = new RegisterCommunication(
				regId, deviceId);
		comm.execute();
		
		final SharedPreferences settings = context.getSharedPreferences("nokiapush", 0);
	    final Editor editor = settings.edit();
	    editor.putString("registrationId", regId);
	    editor.putString("deviceId", deviceId);
	    editor.commit();
	    Log.i("Nokia Push", "Saved registration id and device id to the shared preferences");
	}
	
	private String getDeviceId(Context context) {
	    if (uniqueID == null) {
	        SharedPreferences sharedPrefs = context.getSharedPreferences(
	                PREF_UNIQUE_ID, Context.MODE_PRIVATE);
	        uniqueID = sharedPrefs.getString(PREF_UNIQUE_ID, null);
	        if (uniqueID == null) {
	            uniqueID = UUID.randomUUID().toString();
	            Editor editor = sharedPrefs.edit();
	            editor.putString(PREF_UNIQUE_ID, uniqueID);
	            editor.commit();
	        }
	    }
	    return uniqueID;
	}
	
	@Override
	protected void onError(final Context context, final String errorId) {
		// TODO handle error
		Log.e(this.getClass().getSimpleName(), errorId);
	}

	@Override
	protected void onMessage(final Context context, final Intent intent) {
		Log.w("Nokia Push-Message", "Received message");
		final String payload = intent.getStringExtra("payload");
		Log.d("Nokia Push-Message", "dmControl: payload = " + payload);
		try {
		    
			
		    SpaceData data = SpaceDataJsonParser.parse(new JSONObject(payload));
		    
			SharedPreferences settings = PreferenceManager
					.getDefaultSharedPreferences(context);
			
			final DataEvent statusEvent = (DataEvent) EventBus.getDefault().getStickyEvent(DataEvent.class);
			EventBus.getDefault().postSticky(new DataEvent(data));
			if(statusEvent != null && data.isSpaceOpen() != statusEvent.getData().isSpaceOpen()){
				EventBus.getDefault().postSticky(new StatusChanged(data.isSpaceOpen()));
			}
			
			
			if(settings.getBoolean("notification_preference", true)){
				this.displayNotification(context, data,
						settings.getBoolean("vibration_preference", true), 
						settings.getBoolean("permanent_notification_preference", false));
			}
			
			updateDashClockWidget(context, data);
			updateAppWidget(context, data);
			
		} catch (JSONException e) {
			Log.e("NokiaPush-Message", "JSON-Exception occured!");
		}
	}
	
	

	public void displayNotification(final Context context, final SpaceData data,
			final boolean vibrationEnabled, final boolean permanentNotification) throws JSONException {
		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(ns);

		final boolean permanent = permanentNotification && data.isSpaceOpen();
		
		String timeString = context.getString(R.string.unknown);
		Date time = data.getTime();
        if (time != null) {
            timeString = SpeakingDateFormat.format(time);
        }
		
		int icon;
		CharSequence notificationText;
		final CharSequence contentTitle;
		if(data.isSpaceOpen()){
			icon = R.drawable.notification_open;
			notificationText = context.getString(R.string.space_open, timeString);
			contentTitle = context.getString(R.string.space_open_simple);
		}else{
			icon = R.drawable.notification_closed;
			notificationText = context.getString(R.string.space_closed, timeString);
			contentTitle = context.getString(R.string.space_closed_simple);
		}
		
		long when = System.currentTimeMillis();

		final NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
		builder.setSmallIcon(icon);
		builder.setTicker(notificationText);
		builder.setWhen(when);
		if(data.getMessage()!=null && data.getMessage().length()>0){
			builder.setStyle(new NotificationCompat.BigTextStyle().setSummaryText(notificationText)
	        .bigText("Nachricht: \n" + data.getMessage()));
		}
		if(!permanent){
			builder.setAutoCancel(true);
		 }
		builder.setContentTitle(contentTitle);
		builder.setContentText(notificationText);
		Intent notificationIntent = new Intent(context, StartActivity.class);
		notificationIntent.putExtra("status_json", SpaceDataJsonParser.parse(data).toString());
		PendingIntent contentIntent = PendingIntent.getActivity(context, 0, notificationIntent, 0);
		builder.setContentIntent(contentIntent);
		
		final Notification notification = builder.build();
		if(vibrationEnabled){
			notification.defaults |= Notification.DEFAULT_VIBRATE;
		}
		if(permanent){
			notification.flags |= Notification.FLAG_ONGOING_EVENT;
		}
		mNotificationManager.notify(Constants.NOTIFICATION_ID, notification);
	}
	
	private void updateAppWidget(Context context, SpaceData data) {
        SharedPreferences dataPersistence = getSharedPreferences(Constants.SPACE_DATA_PERSISTENCE, Context.MODE_PRIVATE);
        Editor editor = dataPersistence.edit();
        editor.putBoolean(Constants.SPACE_OPEN_DATA_KEY, data.isSpaceOpen());
        editor.commit();
        
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.appwidget);
        remoteViews.setViewVisibility(R.id.indicatorImage, View.VISIBLE);
        remoteViews.setViewVisibility(R.id.errorText, View.GONE);
            
        if(data.isSpaceOpen()) {
            remoteViews.setImageViewResource(R.id.indicatorImage, R.drawable.banner);
        } else {
            remoteViews.setImageViewResource(R.id.indicatorImage, R.drawable.banner_blur);
        }
        
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        ComponentName componentName =new ComponentName(context, HackerspaceWidgetProvider.class);
        appWidgetManager.updateAppWidget(componentName, remoteViews);
    }
	
	/**
	 * @param context
	 * @param data
	 */
	private void updateDashClockWidget(Context context, SpaceData data) {
		// TODO see issue 
	}


	@Override
	protected void onUnregistered(final Context context, final String regId) {
		final SharedPreferences settings = context.getSharedPreferences("nokiapush", 0);
		final String deviceId = settings.getString("deviceId", this.getDeviceId(context));
		final UnregisterCommunication comm = new UnregisterCommunication(
				deviceId);
		comm.execute();
	}
	
	private class RegisterCommunication extends HackerspaceComm {
		public RegisterCommunication(final String registrationId,
				final String deviceId) {
			super();
			this.httpReq = false;
			this.getReq = false;
			this.servletUrl = "v2/nokiax/register";
			try {
				this.postParams.put("deviceId", URLEncoder.encode(deviceId, Constants.UTF8));
				this.postParams.put("registrationId", URLEncoder.encode(registrationId, Constants.UTF8));
				this.appVersionName = PushIntentService.this.getPackageManager()
						.getPackageInfo(PushIntentService.this.getPackageName(), 0).versionName;
			} catch (NameNotFoundException e) {
				this.appVersionName = "??";
			} catch (UnsupportedEncodingException e) {
				Log.e(RegisterCommunication.class.getName(), "UnsupportedEncodingException occured: " + e.getMessage());
			}
		}

		@Override
		protected void onCancelled() {
			Log.e("Nokia Push Register", "Registration ID couldn't be sent to server");
			// TODO handle cancel
		}

		@Override
		protected void onPostExecute(JSONObject result) {
			Log.i("Nokia Push Register", "Sent registration ID to server");
			// TODO handle result
		}
	}
	
	private class UnregisterCommunication extends HackerspaceComm {
		public UnregisterCommunication(final String deviceId) {
			super();
			this.httpReq = false;
			this.getReq = false;
			this.servletUrl = "v2/nokiax/unregister";
			try {
				this.postParams.put("deviceId", URLEncoder.encode(deviceId,Constants.UTF8));
				this.appVersionName = PushIntentService.this.getPackageManager()
						.getPackageInfo(PushIntentService.this.getPackageName(), 0).versionName;
			} catch (NameNotFoundException e) {
				this.appVersionName = "??";
			} catch (UnsupportedEncodingException e) {
				Log.e(UnregisterCommunication.class.getName(), "UnsupportedEncodingException occured: " + e.getMessage());
			}
		}

		@Override
		protected void onCancelled() {
			Log.e("Nokia Push Unregister", "Unregistering wasn't successfull!");
			// TODO handle cancel
		}

		@Override
		protected void onPostExecute(JSONObject result) {
			Log.i("Nokia Push Unregister", "You successfully unregistered!");
			// TODO handle result
		}
	}
}
