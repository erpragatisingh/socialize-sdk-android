package com.socialize.test.ui.actionbar;

import java.util.concurrent.TimeUnit;
import android.app.Activity;
import com.socialize.test.ui.util.TestUtils;
import com.socialize.ui.actionbar.ActionBarLayoutView;
import com.socialize.ui.comment.CommentActivity;


public class ActionBarCommentTest extends ActionBarTest {
	
	@Override
	public boolean isManual() {
		return false;
	}
	
	public void testCommentButtonOpensCommentView() throws Throwable {
		
		Activity activity = TestUtils.getActivity(this);
		assertTrue(globalLatch.await(10, TimeUnit.SECONDS));
		
		TestUtils.setUpActivityMonitor(CommentActivity.class);
		
		final ActionBarLayoutView actionBar = TestUtils.findView(activity, ActionBarLayoutView.class, 5000);
		
		assertNotNull(actionBar);
		
		runTestOnUiThread(new Runnable() {
			@Override
			public void run() {
				assertTrue(actionBar.getCommentButton().performClick());
			}
		});
		
		Activity waitForActivity = TestUtils.waitForActivity(5000);
		
		assertNotNull(waitForActivity);
		waitForActivity.finish();
	}	
		
}
