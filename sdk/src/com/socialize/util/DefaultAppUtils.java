/*
 * Copyright (c) 2011 Socialize Inc.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.socialize.util;

import java.util.List;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;

import com.socialize.Socialize;
import com.socialize.config.SocializeConfig;
import com.socialize.log.SocializeLogger;
import com.socialize.notifications.SocializeBroadcastReceiver;
import com.socialize.notifications.SocializeC2DMReceiver;

/**
 * @author Jason Polites
 */
public class DefaultAppUtils implements AppUtils {
	
	private String packageName;
	private String appName;
	private SocializeLogger logger;
	private SocializeConfig config;
	
	private boolean locationAvailable = false;
	private boolean locationAssessed = false;
	
	private boolean notificationsAvailable = false;
	private boolean notificationsAssessed = false;
	
	public void init(Context context) {
		packageName = context.getPackageName();
		
		// Try to get the app name 
		try {
			Resources appR = context.getResources(); 
			CharSequence txt = appR.getText(appR.getIdentifier("app_name",  "string", context.getPackageName())); 
			appName = txt.toString();
		} 
		catch (Exception e) {
			String msg = "Failed to locate app_name String from resources.  Make sure this is specified in your AndroidManifest.xml";
			
			if(logger != null) {
				logger.error(msg, e);
			}
			else {
				System.err.println(msg);
				e.printStackTrace();
			}
		}

		if(StringUtils.isEmpty(appName)) {
			appName = packageName;
		}
		
		if(StringUtils.isEmpty(appName)) {
			appName = "A Socialize enabled app";
		}		
	}
	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#getAppUrl()
	 */
	@Override
	public String getAppUrl() {
		String host = config.getProperty(SocializeConfig.REDIRECT_HOST);
		String consumerKey = config.getProperty(SocializeConfig.SOCIALIZE_CONSUMER_KEY);
		if(consumerKey != null) {
			if(!StringUtils.isEmpty(host)) {
				return host + "/a/" + consumerKey;
			}
			else {
				return "http://r.getsocialize.com/a/" + consumerKey;
			}
		}
		else {
			return getMarketUrl();
		}
	}
	
	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#getMarketUrl()
	 */
	@Override
	public String getMarketUrl() {
		StringBuilder builder = new StringBuilder();
		builder.append("https://market.android.com/details?id=");
		builder.append(getAppName());
		return builder.toString();
	}
	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#isActivityAvailable(android.content.Context, java.lang.Class)
	 */
	@Override
	public boolean isActivityAvailable(Context context, Class<?> activity) {
		Intent intent = new Intent(context, activity);
		return isIntentAvailable(context, intent);
	}
	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#isIntentAvailable(android.content.Context, java.lang.String)
	 */
	@Override
	public boolean isIntentAvailable(Context context, String action) {
		Intent intent = new Intent(action);
		return isIntentAvailable(context, intent);
	}
	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#isIntentAvailable(android.content.Context, android.content.Intent)
	 */
	@Override
	public boolean isIntentAvailable(Context context, Intent intent) {
		PackageManager packageManager = context.getPackageManager();
		return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).size() > 0;
	}	
	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#isServiceAvailable(android.content.Context, java.lang.Class)
	 */
	@Override
	public boolean isServiceAvailable(Context context, Class<?> cls) {
	    final PackageManager packageManager = context.getPackageManager();
	    final Intent intent = new Intent(context, cls);
	    return packageManager.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY).size() > 0;
	}
	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#isReceiverAvailable(android.content.Context, java.lang.Class)
	 */
	@Override
	public boolean isReceiverAvailable(Context context, Class<?> cls) {
	    final PackageManager packageManager = context.getPackageManager();
	    final Intent intent = new Intent(context, cls);
	    return packageManager.queryBroadcastReceivers(intent, PackageManager.MATCH_DEFAULT_ONLY).size() > 0;
	}	
	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#isLocationAvaiable(android.content.Context)
	 */
	@Override
	public boolean isLocationAvaiable(Context context) {
		if(!locationAssessed) {
			locationAvailable = hasPermission(context, "android.permission.ACCESS_FINE_LOCATION") || hasPermission(context, "android.permission.ACCESS_COARSE_LOCATION");
			locationAssessed = true;
		}
		return locationAvailable;
	}

	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#isNotificationsAvaiable(android.content.Context)
	 */
	@Override
	public boolean isNotificationsAvaiable(Context context) {

		if(!notificationsAssessed) {
			
		    String permissionString = context.getPackageName() + ".permission.C2D_MESSAGE";
		    
			boolean ok = true;
			
			if(!hasPermission(context, permissionString)) {
				if(logger.isInfoEnabled()) logger.info("Notifications not available, permission [" +
						permissionString +
						"] not specified in AndroidManifest.xml");
				ok = false;
			}
			
			if(!hasPermission(context, "com.google.android.c2dm.permission.RECEIVE")) {
				if(logger.isInfoEnabled()) logger.info("Notifications not available, permission com.google.android.c2dm.permission.RECEIVE not specified in AndroidManifest.xml");
				ok = false;
			}
			
			if(!isReceiverAvailable(context, SocializeBroadcastReceiver.class)) {
				if(logger.isInfoEnabled()) logger.info("Notifications not available. Receiver [" +
						SocializeBroadcastReceiver.class +
						"] not configured in AndroidManifest.xml");
				ok = false;
			}
			
			if(!isServiceAvailable(context, SocializeC2DMReceiver.class)) {
				if(logger.isInfoEnabled()) logger.info("Notifications not available. Service [" +
						SocializeBroadcastReceiver.class +
						"] not configured in AndroidManifest.xml");
				ok = false;
			}			
			
			if(Socialize.getSocialize().getEntityLoader() == null) {
				if(logger.isInfoEnabled()) logger.info("Notifications not available. Entity loader not found.");
				ok = false;
			}
			
			notificationsAvailable = ok;
			notificationsAssessed = true;
		}

		return notificationsAvailable;
	}

	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#hasPermission(android.content.Context, java.lang.String)
	 */
	@Override
	public boolean hasPermission(Context context, String permission) {
		return context.getPackageManager().checkPermission(permission, context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
	}	
	
	public static boolean launchMainApp(Activity origin) {
		Intent mainIntent = getMainAppIntent(origin);
		if(mainIntent != null) {
			origin.startActivity(mainIntent);	
			return true;
		}
		return false;
	}
	
	public static Intent getMainAppIntent(Context context) {
		PackageManager pm = context.getPackageManager();

		Intent mainIntent = new Intent(Intent.ACTION_MAIN);
		mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
		mainIntent.setPackage(context.getPackageName());

		List<ResolveInfo> appList = pm.queryIntentActivities(mainIntent, 0);
		
		if(appList != null && appList.size() > 0) {
			ResolveInfo resolveInfo = appList.get(0);
			mainIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			mainIntent.setComponent(new ComponentName(resolveInfo.activityInfo.applicationInfo.packageName, resolveInfo.activityInfo.name));
			return mainIntent;
		}
		
		return null;
	}
	
	/**
	 * Attempts to get the resource if for the app icon.
	 * @param context
	 * @return
	 */
	public int getAppIconId(Context context) {
		int id = -1;
		try {
			id = context.getResources().getIdentifier("icon", "drawable", context.getPackageName());
		} 
		catch (Exception ignore) {
			if(logger != null && logger.isWarnEnabled()) {
				logger.warn("Failed to retrieve app icon id", ignore);
			}
			else {
				ignore.printStackTrace();
			}
		}
		return id;
	}
	
	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#getAppName()
	 */
	@Override
	public String getAppName() {
		return appName;
	}

	/* (non-Javadoc)
	 * @see com.socialize.util.IAppUtils#getPackageName()
	 */
	@Override
	public String getPackageName() {
		return packageName;
	}

	public void setLogger(SocializeLogger logger) {
		this.logger = logger;
	}

	public void setConfig(SocializeConfig config) {
		this.config = config;
	}
}
