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
package com.socialize.api.action.like;

import android.app.Activity;
import android.app.Dialog;
import com.socialize.ShareUtils;
import com.socialize.api.SocializeSession;
import com.socialize.api.action.SocializeActionUtilsBase;
import com.socialize.entity.Entity;
import com.socialize.entity.Like;
import com.socialize.entity.User;
import com.socialize.error.SocializeApiError;
import com.socialize.error.SocializeException;
import com.socialize.listener.like.LikeAddListener;
import com.socialize.listener.like.LikeDeleteListener;
import com.socialize.listener.like.LikeGetListener;
import com.socialize.listener.like.LikeListListener;
import com.socialize.networks.SocialNetwork;
import com.socialize.ui.auth.AuthRequestDialogListener;
import com.socialize.ui.dialog.AuthRequestDialogFactory;

/**
 * @author Jason Polites
 */
public class SocializeLikeUtils extends SocializeActionUtilsBase implements LikeUtilsProxy {
	
	private AuthRequestDialogFactory authRequestDialogFactory;
	private LikeSystem likeSystem;

	/*
	 * (non-Javadoc)
	 * @see com.socialize.api.action.like.LikeUtilsProxy#like(android.app.Activity, com.socialize.entity.Entity, com.socialize.listener.like.LikeAddListener)
	 */
	@Override
	public void like(Activity context, final Entity e, final LikeAddListener listener) {
		
		final SocializeSession session = getSocialize().getSession();
		
		if(isDisplayAuthDialog()) {
			authRequestDialogFactory.show(context, new AuthRequestDialogListener() {
				@Override
				public void onContinue(Dialog dialog, SocialNetwork... networks) {
					likeSystem.addLike(session, e, SocializeLikeUtils.this.getDefaultShareOptions(), listener);
				}

				@Override
				public void onAuthFail(Dialog dialog, SocialNetwork network, SocializeException error) {
					likeSystem.addLike(session, e, SocializeLikeUtils.this.getDefaultShareOptions(), listener);
				}

				@Override
				public void onCancel(Dialog dialog) {}
			}, ShareUtils.FACEBOOK | ShareUtils.TWITTER);
		}
		else {
			likeSystem.addLike(session, e, SocializeLikeUtils.this.getDefaultShareOptions(), listener);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.socialize.api.action.like.LikeUtilsProxy#unlike(android.app.Activity, com.socialize.entity.Entity, com.socialize.listener.like.LikeDeleteListener)
	 */
	@Override
	public void unlike(Activity context, Entity e, final LikeDeleteListener listener) {
		final SocializeSession session = getSocialize().getSession();
		// Get the like based on the key
		likeSystem.getLike(session, e.getKey(), new LikeGetListener() {
			@Override
			public void onGet(Like entity) {
				if(entity != null) {
					likeSystem.deleteLike(session, entity.getId(), listener);
				}
				else {
					if(listener != null) {
						listener.onDelete();
					}
				}
			}
			
			@Override
			public void onError(SocializeException error) {
				
				if(error instanceof SocializeApiError) {
					if(((SocializeApiError)error).getResultCode() == 404) {
						if(listener != null) {
							listener.onDelete();
						}
						return;
					}
				}
				
				if(listener != null) {
					listener.onError(error);
				}
			}
		});
	}


	/*
	 * (non-Javadoc)
	 * @see com.socialize.api.action.like.LikeUtilsProxy#getLike(android.app.Activity, com.socialize.entity.Entity, com.socialize.listener.like.LikeGetListener)
	 */
	@Override
	public void getLike(Activity context, Entity e, LikeGetListener listener) {
		final SocializeSession session = getSocialize().getSession();
		likeSystem.getLike(session, e.getKey(), listener);
	}

	/*
	 * (non-Javadoc)
	 * @see com.socialize.api.action.like.LikeUtilsProxy#getLikesByUser(android.app.Activity, com.socialize.entity.User, int, int, com.socialize.listener.like.LikeListListener)
	 */
	@Override
	public void getLikesByUser(Activity context, User user, int start, int end, LikeListListener listener) {
		final SocializeSession session = getSocialize().getSession();
		likeSystem.getLikesByUser(session, user.getId(), start, end, listener);
	}

	public void setAuthRequestDialogFactory(AuthRequestDialogFactory authRequestDialogFactory) {
		this.authRequestDialogFactory = authRequestDialogFactory;
	}

	public void setLikeSystem(LikeSystem likeSystem) {
		this.likeSystem = likeSystem;
	}
}
	
	