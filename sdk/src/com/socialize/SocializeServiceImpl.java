/*
 * Copyright (c) 2012 Socialize Inc. 
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
package com.socialize;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.ScrollView;
import com.socialize.android.ioc.IOCContainer;
import com.socialize.android.ioc.Logger;
import com.socialize.api.SocializeSession;
import com.socialize.api.action.ShareType;
import com.socialize.api.action.share.ShareSystem;
import com.socialize.api.action.user.UserSystem;
import com.socialize.auth.AuthProvider;
import com.socialize.auth.AuthProviderInfo;
import com.socialize.auth.AuthProviderInfoBuilder;
import com.socialize.auth.AuthProviderType;
import com.socialize.auth.AuthProviders;
import com.socialize.auth.SocializeAuthProviderInfo;
import com.socialize.auth.UserProviderCredentials;
import com.socialize.auth.UserProviderCredentialsMap;
import com.socialize.concurrent.AsyncTaskManager;
import com.socialize.concurrent.ManagedAsyncTask;
import com.socialize.config.SocializeConfig;
import com.socialize.entity.Comment;
import com.socialize.entity.Entity;
import com.socialize.entity.SocializeAction;
import com.socialize.entity.User;
import com.socialize.error.SocializeException;
import com.socialize.init.SocializeInitializationAsserter;
import com.socialize.ioc.SocializeIOC;
import com.socialize.listener.ListenerHolder;
import com.socialize.listener.SocializeAuthListener;
import com.socialize.listener.SocializeInitListener;
import com.socialize.listener.SocializeListener;
import com.socialize.location.SocializeLocationProvider;
import com.socialize.log.SocializeLogger;
import com.socialize.networks.SocialNetwork;
import com.socialize.networks.SocialNetworkListener;
import com.socialize.networks.facebook.FacebookUtils;
import com.socialize.notifications.C2DMCallback;
import com.socialize.notifications.NotificationChecker;
import com.socialize.notifications.SocializeC2DMReceiver;
import com.socialize.notifications.WakeLock;
import com.socialize.ui.ActivityIOCProvider;
import com.socialize.ui.SocializeEntityLoader;
import com.socialize.ui.action.ActionDetailActivity;
import com.socialize.ui.actionbar.ActionBarListener;
import com.socialize.ui.actionbar.ActionBarOptions;
import com.socialize.ui.actionbar.ActionBarView;
import com.socialize.ui.comment.CommentActivity;
import com.socialize.ui.comment.CommentDetailActivity;
import com.socialize.ui.comment.CommentView;
import com.socialize.ui.comment.OnCommentViewActionListener;
import com.socialize.ui.profile.ProfileActivity;
import com.socialize.util.AppUtils;
import com.socialize.util.ClassLoaderProvider;
import com.socialize.util.DisplayUtils;
import com.socialize.util.EntityLoaderUtils;
import com.socialize.util.ResourceLocator;

/**
 * @author Jason Polites
 */
@SuppressWarnings("deprecation")
public class SocializeServiceImpl implements SocializeService {
	
	static final String receiver = SocializeC2DMReceiver.class.getName();
	
	private SocializeLogger logger;
	private IOCContainer container;
	private SocializeSession session;
	private AuthProviderInfoBuilder authProviderInfoBuilder;
	private SocializeInitializationAsserter asserter;
	private ShareSystem shareSystem;
	private UserSystem userSystem;

	private AuthProviders authProviders;
	private NotificationChecker notificationChecker;
	private AppUtils appUtils;
	private SocializeLocationProvider locationProvider;
	
	private SocializeSystem system = SocializeSystem.getInstance();
	private SocializeConfig config = new SocializeConfig();
	
	private SocializeEntityLoader entityLoader;
	private ListenerHolder listenerHolder;
	
	private String[] initPaths = null;
	private int initCount = 0;
	
	private boolean paused = false;
	
	public SocializeServiceImpl() {
		super();
		this.logger = newLogger();
	}

	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeService#handleBroadcastIntent(android.content.Context, android.content.Intent)
	 */
	@Override
	public boolean handleBroadcastIntent(Context context, Intent intent) {
		
		String action = intent.getAction();
		
		if(action != null && (SocializeC2DMReceiver.C2DM_INTENT.equals(action) || SocializeC2DMReceiver.REGISTRATION_CALLBACK_INTENT.equals(action))) {
			
			if(SocializeC2DMReceiver.C2DM_INTENT.equals(action)) {
				// Check the bundle data for source
				Bundle extras = intent.getExtras();
				if(extras != null) {
					String source = extras.getString(C2DMCallback.SOURCE_KEY);
					if(source != null && source.trim().equalsIgnoreCase(C2DMCallback.SOURCE_SOCIALIZE)) {
						handleIntent(context, intent);
						return true;
					}
				}
			}
			else {
				// Handle registration, but don't return true.
				handleIntent(context, intent);
			}
		}
		
		return false;
	}
	
	protected void handleIntent(Context context, Intent intent) {
		getWakeLock().acquire(context);
		intent.setClassName(context, receiver);
		context.startService(intent);
	}
	
	// So we can mock
	protected WakeLock getWakeLock() {
		return WakeLock.getInstance();
	}

	@Override
	public boolean isSupported(AuthProviderType type) {
		return authProviderInfoBuilder.isSupported(type);
	}

	@Override
	public void isSocializeSupported(Context context) throws SocializeException {
		// Check that we are not LDPI
		DisplayUtils displayUtils = new DisplayUtils();
		displayUtils.init(context);
		if(displayUtils.isLDPI()) {
			throw new SocializeException("Socialize is not supported on low resolution (LDPI) devices");
		}
	}

	/* (non-Javadoc)
	 * @see com.socialize.SocializeService#init(android.content.Context)
	 */
	@Override
	public IOCContainer init(Context context) {
		return init(context, getSystem().getBeanConfig());
	}
	
	/* (non-Javadoc)
	 * @see com.socialize.SocializeService#init(android.content.Context, java.lang.String)
	 */
	@Override
	public IOCContainer init(Context context, String...paths) {
		try {
			return initWithContainer(context, paths);
		}
		catch (Exception e) {
			if(logger != null) {
				logger.error(SocializeLogger.INITIALIZE_FAILED, e);
			}
			else {
				Log.e(SocializeLogger.LOG_TAG, e.getMessage(), e);
			}
		}
		
		return null;
	}
	
	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeService#initAsync(android.content.Context, com.socialize.listener.SocializeInitListener)
	 */
	@Override
	public void initAsync(Context context, SocializeInitListener listener) {
		initAsync(context, listener, getSystem().getBeanConfig());
	}

	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeService#initAsync(android.content.Context, com.socialize.listener.SocializeInitListener, java.lang.String[])
	 */
	@Override
	public void initAsync(Context context, SocializeInitListener listener, String... paths) {
		new InitTask(this, context, paths, listener, logger).execute();
	}

	public synchronized IOCContainer initWithContainer(Context context, String...paths) throws Exception {
		return initWithContainer(context, getSystem().getSystemInitListener(), paths);
	}
	
	public synchronized IOCContainer initWithContainer(Context context, SocializeInitListener listener, String...paths) throws Exception {
		boolean init = false;
		
		// Check socialize is supported on this device.
		isSocializeSupported(context);
		
		String[] localPaths = getInitPaths();
		
		if(paths != null) {

			if(isInitialized()) {
				
				if(localPaths != null) {
					for (String path : paths) {
						if(binarySearch(localPaths, path) < 0) {
							
							if(logger != null && logger.isInfoEnabled()) {
								logger.info("New path found for beans [" +
										path +
										"].  Re-initializing Socialize");
							}
							
							this.initCount = 0;
							
							// Destroy the container so we recreate bean references
							if(container != null) {
								if(logger != null && logger.isDebugEnabled()) {
									logger.debug("Destroying IOC container");
								}
								container.destroy();
							}
							
							init = true;
							
							break;
						}
					}
				}
				else {
					String msg = "Socialize reported as initialize, but no initPaths were found.  This should not happen!";
					if(logger != null) {
						logger.error(msg);
					}
					else {
						System.err.println(msg);
					}
					
					destroy();
					init = true;
				}
			}
			else {
				init = true;
			}
			
			if(init) {
				try {
					Logger.LOG_KEY = Socialize.LOG_KEY;
					Logger.logLevel = Log.WARN;
					
					this.initPaths = paths;
					
					sort(this.initPaths);
					
					if(container == null) {
						container = newSocializeIOC();
					}
					
					ResourceLocator locator = newResourceLocator();
					
					locator.setLogger(newLogger());
					
					ClassLoaderProvider provider = newClassLoaderProvider();
					
					locator.setClassLoaderProvider(provider);
					
					if(logger != null) {
						
						if(logger.isDebugEnabled()) {
							for (String path : paths) {
								logger.debug("Initializing Socialize with path [" +
										path +
										"]");
							}
							
							Logger.logLevel = Log.DEBUG;
						}
						else if(logger.isInfoEnabled()) {
							Logger.logLevel = Log.INFO;
						}
					}	
					
					((SocializeIOC) container).init(context, locator, paths);
					
					init(context, container, listener); // initCount incremented here
				}
				catch (Exception e) {
					throw e;
				}
			}
			else {
				this.initCount++;
			}
			
			// Always set the context on the container
			setContext(context);
		}
		else {
			String msg = "Attempt to initialize Socialize with null bean config paths";
			if(logger != null) {
				logger.error(msg);
			}
			else {
				System.err.println(msg);
			}
		}
		
		return container;
	}
	
	void setContext(Context context) {
		if(container != null) {
			container.setContext(context);
		}
	}

	// So we can mock
	protected String[] getInitPaths() {
		return initPaths;
	}
	
	// So we can mock
	protected SocializeIOC newSocializeIOC() {
		return new SocializeIOC();
	}
	
	// So we can mock
	protected ResourceLocator newResourceLocator() {
		return new ResourceLocator();
	}
	
	// So we can mock
	protected SocializeLogger newLogger() {
		return new SocializeLogger(Socialize.DEFAULT_LOG_LEVEL);
	}
	
	// So we can mock
	protected ClassLoaderProvider newClassLoaderProvider() {
		return new ClassLoaderProvider();
	}
	
	// So we can mock
	protected int binarySearch(String[] array, String str) {
		return Arrays.binarySearch(array, str);
	}
	
	// So we can mock
	protected void sort(Object[] array) {
		Arrays.sort(array);
	}
	
	/* (non-Javadoc)
	 * @see com.socialize.SocializeService#init(android.content.Context, com.socialize.android.ioc.IOCContainer)
	 */
	@Override
	public synchronized void init(Context context, final IOCContainer container) {
		init(context, container, getSystem().getSystemInitListener());
	}
	
	public synchronized void init(Context context, final IOCContainer container, SocializeInitListener listener) {
		if(!isInitialized()) {
			try {
				this.container = container;
				
				this.logger = container.getBean("logger");
				
				this.shareSystem = container.getBean("shareSystem");
				this.userSystem = container.getBean("userSystem");
				this.asserter = container.getBean("initializationAsserter");
				this.authProviders = container.getBean("authProviders");
				this.authProviderInfoBuilder = container.getBean("authProviderInfoBuilder");
				this.notificationChecker = container.getBean("notificationChecker");
				this.appUtils = container.getBean("appUtils");
				this.locationProvider = container.getBean("locationProvider");
				this.listenerHolder = container.getBean("listenerHolder");
				
				SocializeConfig mainConfig = container.getBean("config");
				
				mainConfig.merge(config);
				
				this.config = mainConfig;
				this.initCount++;
				
				verify3rdPartyAuthConfigured();
				
				// Create the entity loader if we have one
				initEntityLoader();

				// Check we are configured ok
				appUtils.checkAndroidManifest(context);
				
				ActivityIOCProvider.getInstance().setContainer(container);
				
				initNotifications(context);
				
				if(listener != null) {
					listener.onInit(context, container);
				}
			}
			catch (Exception e) {
				if(logger != null) {
					logger.error(SocializeLogger.INITIALIZE_FAILED, e);
				}
				else {
					Log.e(SocializeLogger.LOG_TAG, e.getMessage(), e);
				}
			}
		}
		else {
			this.initCount++;
		}
	}
	
	/**
	 * @param context
	 */
	protected synchronized void initNotifications(Context context) {
		if(config.isNotificationsEnabled()) {
			if(notificationChecker != null) {
				notificationChecker.checkRegistrations(context);
			}
		}
	}

	protected synchronized void initEntityLoader() {
		EntityLoaderUtils entityLoaderUtils = container.getBean("entityLoaderUtils");
		entityLoaderUtils.initEntityLoader();
	}
	
	protected void verify3rdPartyAuthConfigured() {
		authProviderInfoBuilder.validateAll();
	}
	
	@Override
	public void saveSession(Context context) {
		userSystem.saveSession(context, session);
	}

	@Override
	public void clear3rdPartySession(Context context, AuthProviderType type) {
		try {
			if(session != null) {
				AuthProvider<AuthProviderInfo> provider = authProviders.getProvider(type);
				
				if(provider != null) {
					UserProviderCredentials userProviderCredentials = session.getUserProviderCredentials(type);
					
					if(userProviderCredentials != null) {
						AuthProviderInfo authProviderInfo = userProviderCredentials.getAuthProviderInfo();
						if(authProviderInfo != null) {
							provider.clearCache(context, authProviderInfo);
						}
					}
				}
				
				session.clear(type);
			}
		}
		finally {
			if(userSystem != null) {
				userSystem.clearSession(type);
			}
		}
	}
	
	@Override
	public void clearSessionCache(Context context) {
		try {
			if(session != null) {
				
				UserProviderCredentialsMap userProviderCredentialsMap = session.getUserProviderCredentials();
				
				if(userProviderCredentialsMap != null) {
					Collection<UserProviderCredentials> values = userProviderCredentialsMap.values();
					for (UserProviderCredentials userProviderCredentials : values) {
						AuthProviderInfo authProviderInfo = userProviderCredentials.getAuthProviderInfo();
						if(authProviderInfo != null) {
							clear3rdPartySession(context, authProviderInfo.getType());
						}
					}
				}
				
				session = null;
			}
		}
		finally {
			if(userSystem != null) {
				userSystem.clearSession();
			}
		}
	}

	/* (non-Javadoc)
	 * @see com.socialize.SocializeService#destroy()
	 */
	@Override
	public void destroy() {
		initCount--;
		
		if(initCount <= 0) {
			destroy(true);
		}
	}
	
	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeService#destroy(boolean)
	 */
	@Override
	public synchronized void destroy(boolean force) {
		if(force) {
			
			if(AsyncTaskManager.isManaged()) {
				AsyncTaskManager.terminateAll(10, TimeUnit.SECONDS);
			}
			
			if(container != null) {
				if(logger != null && logger.isDebugEnabled()) {
					logger.debug("Destroying IOC container");
				}
				container.destroy();
			}
			
			config.destroy();
			system.destroy();
			initCount = 0;
			initPaths = null;
			entityLoader = null;
		}
		else {
			destroy();
		}
	}
	
	@Override
	public synchronized void authenticate(Context context, String consumerKey, String consumerSecret, AuthProviderInfo authProviderInfo, SocializeAuthListener authListener) {
		if(assertInitialized(context, authListener)) {
			userSystem.authenticate(context, consumerKey, consumerSecret, authProviderInfo, authListener, this);
		}
	}
	
	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeService#authenticate(android.content.Context, com.socialize.listener.SocializeAuthListener)
	 */
	@Override
	public synchronized void authenticate(Context context, SocializeAuthListener authListener) {
		if(assertInitialized(context, authListener)) {
			userSystem.authenticate(context, authListener, this);
		}
	}
	
	public synchronized SocializeSession authenticateSynchronous(Context context) throws SocializeException {
		if(userSystem != null) {
			SocializeSession session = userSystem.authenticateSynchronous(context);
			this.session = session;
			return session;
		}
		
		throw new SocializeException("Socialize not initialized");
	}
	
	@Override
	public void authenticate(Context context, AuthProviderType authProviderType, SocializeAuthListener authListener, String... permissions) {
		SocializeConfig config = getConfig();
		String consumerKey = config.getProperty(SocializeConfig.SOCIALIZE_CONSUMER_KEY);
		String consumerSecret = config.getProperty(SocializeConfig.SOCIALIZE_CONSUMER_SECRET);
		AuthProviderInfo authProviderInfo = authProviderInfoBuilder.getFactory(authProviderType).getInstance(permissions);
		authenticate(context, consumerKey, consumerSecret, authProviderInfo,  authListener);
	}

	@Override
	public synchronized void authenticate(Context context, String consumerKey, String consumerSecret, SocializeAuthListener authListener) {
		if(assertInitialized(context, authListener)) {
			userSystem.authenticate(context, consumerKey, consumerSecret, authListener, this);
		}
	}
	
	// So we can mock
	protected SocializeAuthProviderInfo newSocializeAuthProviderInfo() {
		return new SocializeAuthProviderInfo();
	}
	
	@Override
	public void authenticateKnownUser(Context context, UserProviderCredentials userProviderCredentials, SocializeAuthListener authListener) {
		if(assertInitialized(context, authListener)) {
			userSystem.authenticateKnownUser(context, userProviderCredentials, authListener, this);
		}
	}

	protected void logError(String message, Throwable error) {
		if(logger != null) {
			logger.error(message, error);
		}
		else {
			Log.e(SocializeLogger.LOG_TAG, message, error);
		}
	}
	
	protected void logErrorMessage(String message) {
		if(logger != null) {
			logger.error(message);
		}
		else {
			System.err.println(message);
		}
	}
	
	// So we can mock
	protected Comment newComment() {
		return new Comment();
	}
	
	protected void handleActionShare(final Activity activity, final SocialNetwork socialNetwork, SocializeAction action, String shareText, final SocialNetworkListener listener) {
		shareSystem.share(activity, session, action, shareText, null, ShareType.valueOf(socialNetwork), listener);	
	}

	public boolean isInitialized() {
		return this.initCount > 0;
	}
	
	@Override
	public boolean isInitialized(Context context) {
		return this.initCount > 0 && container.getContext() == context;
	}

	/* (non-Javadoc)
	 * @see com.socialize.SocializeService#isAuthenticated()
	 */
	@Override
	public boolean isAuthenticated() {
		return isInitialized() && session != null;
	}
	
	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeService#isAuthenticated(com.socialize.auth.AuthProviderType)
	 */
	@Override
	public boolean isAuthenticated(AuthProviderType providerType) {
		if(isAuthenticated()) {
			
			if(providerType.equals(AuthProviderType.SOCIALIZE)) {
				return true;
			}
			
			UserProviderCredentials userProviderCredentials = session.getUserProviderCredentials(providerType);
			
			if(userProviderCredentials == null) {
				// Legacy
				return isAuthenticatedLegacy(providerType);
			}
			else {
				return true;
			}
		}
		return false;
	}
	
	protected boolean isAuthenticatedLegacy(AuthProviderType providerType) {
		AuthProviderType authProviderType = session.getAuthProviderType();
		if(authProviderType == null) {
			return false;
		}
		else {
			return (authProviderType.equals(providerType));
		}
	}

	protected boolean assertAuthenticated(SocializeListener listener) {
		if(asserter != null) {
			return asserter.assertAuthenticated(this, session, listener);
		}
		
		if(session != null) {
			return true;
		}
		else {
			if(listener != null) {
				if(logger != null && logger.isInitialized()) {
					listener.onError(new SocializeException(logger.getMessage(SocializeLogger.NOT_AUTHENTICATED)));
				}
				else {
					listener.onError(new SocializeException("Not authenticated"));
				}
			}
			if(logger != null && logger.isInitialized()) {
				logger.error(SocializeLogger.NOT_AUTHENTICATED);
			}
			else {
				System.err.println("Not authenticated");
			}
		}
		
		return false;
	}
	
	protected boolean assertInitialized(Context context, SocializeListener listener) {
		if(asserter != null) {
			return asserter.assertInitialized(context, this, listener);
		}
		
		if(!isInitialized()) {
			if(listener != null) {
				if(logger != null && logger.isInitialized()) {
					listener.onError(new SocializeException(logger.getMessage(SocializeLogger.NOT_INITIALIZED)));
				}
				else {
					listener.onError(new SocializeException("Not initialized"));
				}
			}
			if(logger != null) {
				if(logger.isInitialized()) {
					logger.error(SocializeLogger.NOT_INITIALIZED);
				}
				else {
					logger.error("Socialize Not initialized!");
				}
			}
		}
		
		return isInitialized();		
	}
	
	

	/* (non-Javadoc)
	 * @see com.socialize.SocializeService#getSession()
	 */
	@Override
	public SocializeSession getSession() {
		return session;
	}

	/*
	 * (non-Javadoc)
	 * @see com.socialize.api.SocializeSessionConsumer#setSession(com.socialize.api.SocializeSession)
	 */
	@Override
	public void setSession(SocializeSession session) {
		this.session = session;
	}
	
	public void setLogger(SocializeLogger logger) {
		this.logger = logger;
	}
	
	public SocializeConfig getConfig() {
		return config;
	}
	
	public static class InitTask extends ManagedAsyncTask<Void, Void, IOCContainer> {
		private Context context;
		private String[] paths;
		private Exception error;
		private SocializeInitListener listener;
		private SocializeServiceImpl service;
		private SocializeLogger logger;
		
		public InitTask(
				SocializeServiceImpl service, 
				Context context, 
				String[] paths, 
				SocializeInitListener listener, 
				SocializeLogger logger) {
			super();
			this.context = context;
			this.paths = paths;
			this.listener = listener;
			this.service = service;
			this.logger = logger;
		}

		@Override
		public IOCContainer doInBackground(Void... params) {
			try {
				// Force null listener.  This will be called in postExecute
				return service.initWithContainer(context, null, paths);
			}
			catch (Exception e) {
				error = e;
				return null;
			}
		}

		@Override
		public void onPostExecuteManaged(IOCContainer result) {
			if(result == null) {
				final String errorMessage = "Failed to initialize Socialize instance";
				
				if(listener != null) {
					if(error != null) {
						listener.onError(SocializeException.wrap(error));
					}
					else {
						listener.onError(new SocializeException(errorMessage));
					}
				}
				else {
					if(logger != null) {
						if(error != null) {
							logger.error(errorMessage, error);
						}
						else {
							logger.error(errorMessage);
						}
					}
					else {
						if(error != null) {
							Log.e(SocializeLogger.LOG_TAG, errorMessage, error);
						}
						else {
							System.err.println(errorMessage);
						}
					}
				}
			}
			else {
				if(listener != null) {
					listener.onInit(context, result);
				}
			}
		}
	};

	/**
	 * EXPERT ONLY (Not documented)
	 * @return
	 */
	IOCContainer getContainer() {
		return container;
	}


	
	@Override
	public boolean canShare(Context context, ShareType shareType) {
		return shareSystem.canShare(context, shareType);
	}

	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeService#getEntityLoader()
	 */
	@Override
	public SocializeEntityLoader getEntityLoader() {
		return entityLoader;
	}
	
	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeService#getSystem()
	 */
	@Override
	public SocializeSystem getSystem() {
		return system;
	}
	
	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeService#setEntityLoader(com.socialize.ui.SocializeEntityLoader)
	 */
	@Override
	public void setEntityLoader(SocializeEntityLoader entityLoader) {
		this.entityLoader = entityLoader;
	}
	

	@Override
	public View showActionBar(Activity parent, int resId, Entity entity) {
		return showActionBar(parent, resId, entity, null, null);
	}

	@Override
	public View showActionBar(Activity parent, int resId, Entity entity, ActionBarListener listener) {
		return showActionBar(parent, resId, entity, null, listener);
	}

	@Override
	public View showActionBar(Activity parent, int resId, Entity entity, ActionBarOptions options) {
		return showActionBar(parent, resId, entity, options, null);
	}

	@Override
	public View showActionBar(Activity parent, int resId, Entity entity, ActionBarOptions options, ActionBarListener listener) {
		return showActionBar(parent, inflateView(parent, resId) , entity, options, listener);
	}

	@Override
	public View showActionBar(Activity parent, View original, Entity entity) {
		return showActionBar(parent, original, entity, null, null);
	}
	
	@Override
	public View showActionBar(Activity parent, View original, Entity entity, ActionBarOptions options) {
		return showActionBar(parent, original, entity, options, null);
	}

	@Override
	public View showActionBar(Activity parent, View original, Entity entity, ActionBarListener listener) {
		return showActionBar(parent, original, entity, null, listener);
	}

	@Override
	public View showActionBar(Activity parent, View original, Entity entity, ActionBarOptions options, ActionBarListener listener) {
		RelativeLayout parentRelLyout = newRelativeLayout(parent);
		RelativeLayout.LayoutParams barLayoutParams = newLayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
		parentRelLyout.setLayoutParams(barLayoutParams);
		
		ActionBarView socializeActionBar = newActionBarView(parent);
		socializeActionBar.setActionBarListener(listener);
		socializeActionBar.assignId(original);
		socializeActionBar.setEntity(entity);
		
		LayoutParams socializeActionBarParams = newLayoutParams(LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT);
		socializeActionBarParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
		
		socializeActionBar.setLayoutParams(socializeActionBarParams);
		
		boolean addScrollView = true;
		
		if(options != null) {
			addScrollView = options.isAddScrollView();
		}
		
		View contentView = null;
		
		if(addScrollView && !(original instanceof ScrollView) && !(original instanceof ListView) ) {
			RelativeLayout.LayoutParams scrollViewParams = newLayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
			
			scrollViewParams.addRule(RelativeLayout.ABOVE, socializeActionBar.getId());
			
			ScrollView scrollView = newScrollView(parent);
			scrollView.setFillViewport(true);
			scrollView.setScrollContainer(false);
			scrollView.setLayoutParams(scrollViewParams);
			scrollView.addView(original);
			
			contentView = scrollView;
		}
		else {
			ViewGroup.LayoutParams originalParams = original.getLayoutParams();
			LayoutParams updatedOriginalParams = null;
			
			if(originalParams != null) {
				updatedOriginalParams = newLayoutParams(originalParams);
			}
			else {
				updatedOriginalParams = newLayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
			}
			
			updatedOriginalParams.addRule(RelativeLayout.ABOVE, socializeActionBar.getId());
			
			original.setLayoutParams(updatedOriginalParams);
			
			contentView = original;
		}		

		parentRelLyout.addView(contentView);
		parentRelLyout.addView(socializeActionBar);
		
		return parentRelLyout;
	}
	

	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeUI#showActionDetailViewForResult(android.app.Activity, com.socialize.entity.User, com.socialize.entity.SocializeAction, int)
	 */
	@Deprecated
	@Override
	public void showActionDetailViewForResult(Activity context, User user, SocializeAction action, int requestCode) {
		showActionDetailView(context, user, action);
	}

	@Override
	public void showActionDetailView(Activity context, User user, SocializeAction action) {
		Intent i = newIntent(context, ActionDetailActivity.class);
		i.putExtra(Socialize.USER_ID, user.getId().toString());
		
		if(action != null) {
			i.putExtra(Socialize.ACTION_ID, action.getId().toString());
		}
		
		try {
			// MUST be FLAG_ACTIVITY_SINGLE_TOP because we only code to onNewIntent
			i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
			
			context.startActivity(i);
		} 
		catch (ActivityNotFoundException e) {
			// Revert to legacy
			i.setClass(context, CommentDetailActivity.class);
			try {
				context.startActivity(i);
				Log.w(Socialize.LOG_KEY, "Using legacy CommentDetailActivity.  Please update your AndroidManifest.xml to use ActionDetailActivity");
			} 
			catch (ActivityNotFoundException e2) {
				Log.e(Socialize.LOG_KEY, "Could not find ActionDetailActivity.  Make sure you have added this to your AndroidManifest.xml");
			}
		}		
	}

	/**
	 * Shows the comments list for the given entity.
	 * @param context
	 * @param entity
	 */
	@Override
	public void showCommentView(Activity context, Entity entity) {
		showCommentView(context, entity, null);
	}

	/**
	 * Shows the comments list for the given entity.
	 * @param context
	 * @param entity
	 * @param listener
	 */
	@Override
	public void showCommentView(Activity context, Entity entity, OnCommentViewActionListener listener) {
		if(listener != null) {
			listenerHolder.push(CommentView.COMMENT_LISTENER, listener);
//			Socialize.STATIC_LISTENERS.put(CommentView.COMMENT_LISTENER, listener);
		}

		try {
			Intent i = newIntent(context, CommentActivity.class);
			i.putExtra(Socialize.ENTITY_OBJECT, entity);
			context.startActivity(i);
		} 
		catch (ActivityNotFoundException e) {
			Log.e(Socialize.LOG_KEY, "Could not find CommentActivity.  Make sure you have added this to your AndroidManifest.xml");
		} 
	}

	@Override
	public void showUserProfileView(Activity context, Long userId) {
		Intent i = newIntent(context, ProfileActivity.class);
		i.putExtra(Socialize.USER_ID, userId.toString());
		try {
			context.startActivity(i);
		} 
		catch (ActivityNotFoundException e) {
			Log.e(Socialize.LOG_KEY, "Could not find ProfileActivity.  Make sure you have added this to your AndroidManifest.xml");
		}
	}

	@Override
	public void showUserProfileViewForResult(Activity context, Long userId, int requestCode) {
		Intent i = newIntent(context, ProfileActivity.class);
		i.putExtra(Socialize.USER_ID, userId.toString());
		
		try {
			context.startActivityForResult(i, requestCode);
		} 
		catch (ActivityNotFoundException e) {
			Log.e(Socialize.LOG_KEY, "Could not find ProfileActivity.  Make sure you have added this to your AndroidManifest.xml");
		}	
	}

	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeUI#getDrawable(java.lang.String, boolean)
	 */
	@Deprecated
	@Override
	public Drawable getDrawable(String name, boolean eternal) {
		return null;
	}

	/*
	 * (non-Javadoc)
	 * @see com.socialize.SocializeUI#getDrawable(java.lang.String)
	 */
	@Deprecated
	@Override
	public Drawable getDrawable(String name) {
		return null; 
	}	
	
	@Override
	public SocializeLogger getLogger() {
		return logger;
	}

	@Override
	public void onPause(Context context) {
		paused = true;
		if(locationProvider != null) {
			locationProvider.pause(context);	
		}
	}

	@Override
	public void onResume(Context context) {
		if(paused) {
			try {
				FacebookUtils.extendAccessToken(context);
			}
			catch (Exception e) {
				Log.e(SocializeLogger.LOG_TAG, "Error occurred on resume", e);
			}
			paused = false;
		}
	}
	
	@Override
	public void onCreate(Context context, Bundle savedInstanceState) {}

	@Override
	public void onDestroy(Context context) {}

	protected void setShareSystem(ShareSystem shareSystem) {
		this.shareSystem = shareSystem;
	}

	protected void setUserSystem(UserSystem userSystem) {
		this.userSystem = userSystem;
	}

	protected void setAuthProviders(AuthProviders authProviders) {
		this.authProviders = authProviders;
	}

	protected void setAuthProviderInfoBuilder(AuthProviderInfoBuilder authProviderInfoBuilder) {
		this.authProviderInfoBuilder = authProviderInfoBuilder;
	}
	
	protected LayoutParams newLayoutParams(int width, int height) {
		return new LayoutParams(width, height);
	}
	
	protected RelativeLayout.LayoutParams newLayoutParams(android.view.ViewGroup.LayoutParams source) {
		return new RelativeLayout.LayoutParams(source);
	}
	
	protected Intent newIntent(Activity context, Class<?> cls) {
		return new Intent(context, cls);
	}

	protected RelativeLayout newRelativeLayout(Activity parent) {
		return new RelativeLayout(parent);
	}

	protected ScrollView newScrollView(Activity parent) {
		return new ScrollView(parent);
	}

	protected ActionBarView newActionBarView(Activity parent) {
		return new ActionBarView(parent);
	}

	protected View inflateView(Activity parent, int resId) {
		LayoutInflater layoutInflater = (LayoutInflater) parent.getSystemService(Context.LAYOUT_INFLATER_SERVICE); 
		return layoutInflater.inflate(resId, null);
	}
}
