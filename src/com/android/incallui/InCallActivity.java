/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Bundle;
import android.telephony.DisconnectCause;
import android.text.TextUtils;
import android.view.ViewTreeObserver;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.android.phone.common.animation.AnimUtils;
import com.android.phone.common.animation.AnimationListenerAdapter;
import com.android.contacts.common.interactions.TouchPointManager;
import com.android.incallui.Call.State;

import java.util.Locale;

/**
 * Phone app "in call" screen.
 */
public class InCallActivity extends Activity {

    public static final String SHOW_DIALPAD_EXTRA = "InCallActivity.show_dialpad";
    public static final String DIALPAD_TEXT_EXTRA = "InCallActivity.dialpad_text";
    public static final String NEW_OUTGOING_CALL = "InCallActivity.new_outgoing_call";

    private static final int INVALID_RES_ID = -1;

    private CallButtonFragment mCallButtonFragment;
    private CallCardFragment mCallCardFragment;
    private AnswerFragment mAnswerFragment;
    private DialpadFragment mDialpadFragment;
    private ConferenceManagerFragment mConferenceManagerFragment;
    private FragmentManager mChildFragmentManager;

    private boolean mIsForegroundActivity;
    private AlertDialog mDialog;

    /** Use to pass 'showDialpad' from {@link #onNewIntent} to {@link #onResume} */
    private boolean mShowDialpadRequested;

    /** Use to determine if the dialpad should be animated on show. */
    private boolean mAnimateDialpadOnShow;

    /** Use to determine the DTMF Text which should be pre-populated in the dialpad. */
    private String mDtmfText;

    /** Use to pass parameters for showing the PostCharDialog to {@link #onResume} */
    private boolean mShowPostCharWaitDialogOnResume;
    private String mShowPostCharWaitDialogCallId;
    private String mShowPostCharWaitDialogChars;

    private boolean mIsLandscape;
    private Animation mSlideIn;
    private Animation mSlideOut;
    AnimationListenerAdapter mSlideOutListener = new AnimationListenerAdapter() {
        @Override
        public void onAnimationEnd(Animation animation) {
            showDialpad(false);
        }
    };

    /**
     * Stores the current orientation of the activity.  Used to determine if a change in orientation
     * has occurred.
     */
    private int mCurrentOrientation;

    @Override
    protected void onCreate(Bundle icicle) {
        Log.d(this, "onCreate()...  this = " + this);

        super.onCreate(icicle);

        // set this flag so this activity will stay in front of the keyguard
        // Have the WindowManager filter out touch events that are "too fat".
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;

        getWindow().addFlags(flags);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // TODO(klp): Do we need to add this back when prox sensor is not available?
        // lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;

        // Inflate everything in incall_screen.xml and add it to the screen.
        setContentView(R.layout.incall_screen);

        initializeInCall();

        internalResolveIntent(getIntent());

        mCurrentOrientation = getResources().getConfiguration().orientation;
        mIsLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        final boolean isRtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) ==
                View.LAYOUT_DIRECTION_RTL;

        if (mIsLandscape) {
            mSlideIn = AnimationUtils.loadAnimation(this,
                    isRtl ? R.anim.dialpad_slide_in_left : R.anim.dialpad_slide_in_right);
            mSlideOut = AnimationUtils.loadAnimation(this,
                    isRtl ? R.anim.dialpad_slide_out_left : R.anim.dialpad_slide_out_right);
        } else {
            mSlideIn = AnimationUtils.loadAnimation(this, R.anim.dialpad_slide_in_bottom);
            mSlideOut = AnimationUtils.loadAnimation(this, R.anim.dialpad_slide_out_bottom);
        }

        mSlideIn.setInterpolator(AnimUtils.EASE_IN);
        mSlideOut.setInterpolator(AnimUtils.EASE_OUT);

        mSlideOut.setAnimationListener(mSlideOutListener);

        if (icicle != null) {
            // If the dialpad was shown before, set variables indicating it should be shown and
            // populated with the previous DTMF text.  The dialpad is actually shown and populated
            // in onResume() to ensure the hosting CallCardFragment has been inflated and is ready
            // to receive it.
            mShowDialpadRequested = icicle.getBoolean(SHOW_DIALPAD_EXTRA);
            mAnimateDialpadOnShow = false;
            mDtmfText = icicle.getString(DIALPAD_TEXT_EXTRA);
        }
        Log.d(this, "onCreate(): exit");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putBoolean(SHOW_DIALPAD_EXTRA, mCallButtonFragment.isDialpadVisible());
        if (mDialpadFragment != null) {
            out.putString(DIALPAD_TEXT_EXTRA, mDialpadFragment.getDtmfText());
        }
    }

    @Override
    protected void onStart() {
        Log.d(this, "onStart()...");
        super.onStart();

        // setting activity should be last thing in setup process
        InCallPresenter.getInstance().setActivity(this);
    }

    @Override
    protected void onResume() {
        Log.i(this, "onResume()...");
        super.onResume();

        mIsForegroundActivity = true;
        InCallPresenter.getInstance().onUiShowing(true);

        if (mShowDialpadRequested) {
            mCallButtonFragment.displayDialpad(true /* show */,
                    mAnimateDialpadOnShow /* animate */);
            mShowDialpadRequested = false;
            mAnimateDialpadOnShow = false;

            if (mDialpadFragment != null) {
                mDialpadFragment.setDtmfText(mDtmfText);
                mDtmfText = null;
            }
        }

        if (mShowPostCharWaitDialogOnResume) {
            showPostCharWaitDialog(mShowPostCharWaitDialogCallId, mShowPostCharWaitDialogChars);
        }
    }

    // onPause is guaranteed to be called when the InCallActivity goes
    // in the background.
    @Override
    protected void onPause() {
        Log.d(this, "onPause()...");
        super.onPause();

        mIsForegroundActivity = false;

        if (mDialpadFragment != null ) {
            mDialpadFragment.onDialerKeyUp(null);
        }

        InCallPresenter.getInstance().onUiShowing(false);
    }

    @Override
    protected void onStop() {
        Log.d(this, "onStop()...");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(this, "onDestroy()...  this = " + this);

        InCallPresenter.getInstance().setActivity(null);

        super.onDestroy();
    }

    /**
     * Returns true when theActivity is in foreground (between onResume and onPause).
     */
    /* package */ boolean isForegroundActivity() {
        return mIsForegroundActivity;
    }

    private boolean hasPendingErrorDialog() {
        return mDialog != null;
    }

    /**
     * Dismisses the in-call screen.
     *
     * We never *really* finish() the InCallActivity, since we don't want to get destroyed and then
     * have to be re-created from scratch for the next call.  Instead, we just move ourselves to the
     * back of the activity stack.
     *
     * This also means that we'll no longer be reachable via the BACK button (since moveTaskToBack()
     * puts us behind the Home app, but the home app doesn't allow the BACK key to move you any
     * farther down in the history stack.)
     *
     * (Since the Phone app itself is never killed, this basically means that we'll keep a single
     * InCallActivity instance around for the entire uptime of the device.  This noticeably improves
     * the UI responsiveness for incoming calls.)
     */
    @Override
    public void finish() {
        Log.i(this, "finish().  Dialog showing: " + (mDialog != null));

        // skip finish if we are still showing a dialog.
        if (!hasPendingErrorDialog() && !mAnswerFragment.hasPendingDialogs()) {
            super.finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.d(this, "onNewIntent: intent = " + intent);

        // We're being re-launched with a new Intent.  Since it's possible for a
        // single InCallActivity instance to persist indefinitely (even if we
        // finish() ourselves), this sequence can potentially happen any time
        // the InCallActivity needs to be displayed.

        // Stash away the new intent so that we can get it in the future
        // by calling getIntent().  (Otherwise getIntent() will return the
        // original Intent from when we first got created!)
        setIntent(intent);

        // Activities are always paused before receiving a new intent, so
        // we can count on our onResume() method being called next.

        // Just like in onCreate(), handle the intent.
        internalResolveIntent(intent);
    }

    @Override
    public void onBackPressed() {
        Log.d(this, "onBackPressed()...");

        // BACK is also used to exit out of any "special modes" of the
        // in-call UI:

        if (!mCallCardFragment.isVisible()) {
            return;
        }

        if (mDialpadFragment != null && mDialpadFragment.isVisible()) {
            mCallButtonFragment.displayDialpad(false /* show */, true /* animate */);
            return;
        } else if (mConferenceManagerFragment.isVisible()) {
            mConferenceManagerFragment.setVisible(false);
            return;
        }

        // Always disable the Back key while an incoming call is ringing
        final Call call = CallList.getInstance().getIncomingCall();
        if (call != null) {
            Log.d(this, "Consume Back press for an incoming call");
            return;
        }

        // Nothing special to do.  Fall back to the default behavior.
        super.onBackPressed();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // push input to the dialer.
        if (mDialpadFragment != null && (mDialpadFragment.isVisible()) &&
                (mDialpadFragment.onDialerKeyUp(event))){
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_CALL) {
            // Always consume CALL to be sure the PhoneWindow won't do anything with it
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL:
                boolean handled = InCallPresenter.getInstance().handleCallKey();
                if (!handled) {
                    Log.w(this, "InCallActivity should always handle KEYCODE_CALL in onKeyDown");
                }
                // Always consume CALL to be sure the PhoneWindow won't do anything with it
                return true;

            // Note there's no KeyEvent.KEYCODE_ENDCALL case here.
            // The standard system-wide handling of the ENDCALL key
            // (see PhoneWindowManager's handling of KEYCODE_ENDCALL)
            // already implements exactly what the UI spec wants,
            // namely (1) "hang up" if there's a current active call,
            // or (2) "don't answer" if there's a current ringing call.

            case KeyEvent.KEYCODE_CAMERA:
                // Disable the CAMERA button while in-call since it's too
                // easy to press accidentally.
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                // Ringer silencing handled by PhoneWindowManager.
                break;

            case KeyEvent.KEYCODE_MUTE:
                // toggle mute
                TelecommAdapter.getInstance().mute(!AudioModeProvider.getInstance().getMute());
                return true;

            // Various testing/debugging features, enabled ONLY when VERBOSE == true.
            case KeyEvent.KEYCODE_SLASH:
                if (Log.VERBOSE) {
                    Log.v(this, "----------- InCallActivity View dump --------------");
                    // Dump starting from the top-level view of the entire activity:
                    Window w = this.getWindow();
                    View decorView = w.getDecorView();
                    Log.d(this, "View dump:" + decorView);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_EQUALS:
                // TODO: Dump phone state?
                break;
        }

        if (event.getRepeatCount() == 0 && handleDialerKeyDown(keyCode, event)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private boolean handleDialerKeyDown(int keyCode, KeyEvent event) {
        Log.v(this, "handleDialerKeyDown: keyCode " + keyCode + ", event " + event + "...");

        // As soon as the user starts typing valid dialable keys on the
        // keyboard (presumably to type DTMF tones) we start passing the
        // key events to the DTMFDialer's onDialerKeyDown.
        if (mDialpadFragment != null && mDialpadFragment.isVisible()) {
            return mDialpadFragment.onDialerKeyDown(event);

            // TODO: If the dialpad isn't currently visible, maybe
            // consider automatically bringing it up right now?
            // (Just to make sure the user sees the digits widget...)
            // But this probably isn't too critical since it's awkward to
            // use the hard keyboard while in-call in the first place,
            // especially now that the in-call UI is portrait-only...
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        InCallPresenter.getInstance().getProximitySensor().onConfigurationChanged(config);
        Log.d(this, "onConfigurationChanged "+config.orientation);

        // Check to see if the orientation changed to prevent triggering orientation change events
        // for other configuration changes.
        if (config.orientation != mCurrentOrientation) {
            mCurrentOrientation = config.orientation;
            InCallPresenter.getInstance().onDeviceRotationChange(
                    getWindowManager().getDefaultDisplay().getRotation());
            InCallPresenter.getInstance().onDeviceOrientationChange(mCurrentOrientation);
        }
        super.onConfigurationChanged(config);
    }

    public CallButtonFragment getCallButtonFragment() {
        return mCallButtonFragment;
    }

    public CallCardFragment getCallCardFragment() {
        return mCallCardFragment;
    }

    private void internalResolveIntent(Intent intent) {
        final String action = intent.getAction();

        if (action.equals(intent.ACTION_MAIN)) {
            // This action is the normal way to bring up the in-call UI.
            //
            // But we do check here for one extra that can come along with the
            // ACTION_MAIN intent:

            if (intent.hasExtra(SHOW_DIALPAD_EXTRA)) {
                // SHOW_DIALPAD_EXTRA can be used here to specify whether the DTMF
                // dialpad should be initially visible.  If the extra isn't
                // present at all, we just leave the dialpad in its previous state.

                final boolean showDialpad = intent.getBooleanExtra(SHOW_DIALPAD_EXTRA, false);
                Log.d(this, "- internalResolveIntent: SHOW_DIALPAD_EXTRA: " + showDialpad);

                relaunchedFromDialer(showDialpad);
            }

            if (intent.getBooleanExtra(NEW_OUTGOING_CALL, false)) {
                intent.removeExtra(NEW_OUTGOING_CALL);

                Call call = CallList.getInstance().getOutgoingCall();
                if (call == null) {
                    call = CallList.getInstance().getPendingOutgoingCall();
                }

                Bundle extras = call.getTelecommCall().getDetails().getExtras();
                Point touchPoint = (Point) (extras == null?
                        null : extras.getParcelable(TouchPointManager.TOUCH_POINT));
                mCallCardFragment.animateForNewOutgoingCall(touchPoint, call);
            }

            if (CallList.getInstance().getWaitingForAccountCall() != null) {
                mCallCardFragment.setVisible(false);
                SelectPhoneAccountDialogFragment.show(getFragmentManager());
            } else {
                mCallCardFragment.setVisible(true);
            }

            return;
        }
    }

    private void relaunchedFromDialer(boolean showDialpad) {
        mShowDialpadRequested = showDialpad;
        mAnimateDialpadOnShow = true;

        if (mShowDialpadRequested) {
            // If there's only one line in use, AND it's on hold, then we're sure the user
            // wants to use the dialpad toward the exact line, so un-hold the holding line.
            final Call call = CallList.getInstance().getActiveOrBackgroundCall();
            if (call != null && call.getState() == State.ONHOLD) {
                TelecommAdapter.getInstance().unholdCall(call.getId());
            }
        }
    }

    private void initializeInCall() {
        if (mCallCardFragment == null) {
            mCallCardFragment = (CallCardFragment) getFragmentManager()
                    .findFragmentById(R.id.callCardFragment);
        }

        mChildFragmentManager = mCallCardFragment.getChildFragmentManager();

        if (mCallButtonFragment == null) {
            mCallButtonFragment = (CallButtonFragment) mChildFragmentManager
                    .findFragmentById(R.id.callButtonFragment);
            mCallButtonFragment.getView().setVisibility(View.INVISIBLE);
        }

        if (mAnswerFragment == null) {
            mAnswerFragment = (AnswerFragment) mChildFragmentManager
                    .findFragmentById(R.id.answerFragment);
        }

        if (mConferenceManagerFragment == null) {
            mConferenceManagerFragment = (ConferenceManagerFragment) getFragmentManager()
                    .findFragmentById(R.id.conferenceManagerFragment);
            mConferenceManagerFragment.getView().setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Simulates a user click to hide the dialpad. This will update the UI to show the call card,
     * update the checked state of the dialpad button, and update the proximity sensor state.
     */
    public void hideDialpadForDisconnect() {
        mCallButtonFragment.displayDialpad(false /* show */, true /* animate */);
    }

    public void dismissKeyguard(boolean dismiss) {
        if (dismiss) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    private void showDialpad(boolean showDialpad) {
        // If the dialpad is being shown and it has not already been loaded, replace the dialpad
        // placeholder with the actual fragment before continuing.
        if (mDialpadFragment == null && showDialpad) {
            final FragmentTransaction loadTransaction = mChildFragmentManager.beginTransaction();
            View fragmentContainer = findViewById(R.id.dialpadFragmentContainer);
            mDialpadFragment = new DialpadFragment();
            loadTransaction.replace(fragmentContainer.getId(), mDialpadFragment,
                    DialpadFragment.class.getName());
            loadTransaction.commitAllowingStateLoss();
            mChildFragmentManager.executePendingTransactions();
        }

        final FragmentTransaction ft = mChildFragmentManager.beginTransaction();
        if (showDialpad) {
            ft.show(mDialpadFragment);
        } else {
            ft.hide(mDialpadFragment);
        }
        ft.commitAllowingStateLoss();
    }

    public void displayDialpad(boolean showDialpad, boolean animate) {
        // If the dialpad is already visible, don't animate in. If it's gone, don't animate out.
        if ((showDialpad && isDialpadVisible()) || (!showDialpad && !isDialpadVisible())) {
            return;
        }
        // We don't do a FragmentTransaction on the hide case because it will be dealt with when
        // the listener is fired after an animation finishes.
        if (!animate) {
            showDialpad(showDialpad);
        } else {
            if (showDialpad) {
                showDialpad(true);
                mDialpadFragment.animateShowDialpad();
                mCallCardFragment.onDialpadShow();
            } else {
                mCallCardFragment.onDialpadHide();
            }
            mDialpadFragment.getView().startAnimation(showDialpad ? mSlideIn : mSlideOut);
        }

        InCallPresenter.getInstance().getProximitySensor().onDialpadVisible(showDialpad);
    }

    public boolean isDialpadVisible() {
        return mDialpadFragment != null && mDialpadFragment.isVisible();
    }

    public void displayManageConferencePanel(boolean showPanel) {
        if (showPanel) {
            mConferenceManagerFragment.setVisible(true);
        }
    }

    public void showPostCharWaitDialog(String callId, String chars) {
        if (isForegroundActivity()) {
            final PostCharDialogFragment fragment = new PostCharDialogFragment(callId,  chars);
            fragment.show(getFragmentManager(), "postCharWait");

            mShowPostCharWaitDialogOnResume = false;
            mShowPostCharWaitDialogCallId = null;
            mShowPostCharWaitDialogChars = null;
        } else {
            mShowPostCharWaitDialogOnResume = true;
            mShowPostCharWaitDialogCallId = callId;
            mShowPostCharWaitDialogChars = chars;
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (mCallCardFragment != null) {
            mCallCardFragment.dispatchPopulateAccessibilityEvent(event);
        }
        return super.dispatchPopulateAccessibilityEvent(event);
    }

    /**
     * @param cause disconnect cause as defined in {@link DisconnectCause}
     */
    public void maybeShowErrorDialogOnDisconnect(int cause) {
        Log.d(this, "maybeShowErrorDialogOnDisconnect");

        if (!isFinishing()) {
            final int resId = getResIdForDisconnectCause(cause);
            if (resId != INVALID_RES_ID) {
                showErrorDialog(resId);
            }
        }
    }

    public void dismissPendingDialogs() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        mAnswerFragment.dismissPendingDialogues();
    }

    /**
     * Utility function to bring up a generic "error" dialog.
     */
    private void showErrorDialog(int resId) {
        final CharSequence msg = getResources().getText(resId);
        Log.i(this, "Show Dialog: " + msg);

        dismissPendingDialogs();

        mDialog = new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onDialogDismissed();
                    }})
                .setOnCancelListener(new OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        onDialogDismissed();
                    }})
                .create();

        mDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mDialog.show();
    }

    private int getResIdForDisconnectCause(int cause) {
        switch (cause) {
            case DisconnectCause.CALL_BARRED:
                return R.string.callFailed_cb_enabled;
            case DisconnectCause.FDN_BLOCKED:
                return R.string.callFailed_fdn_only;
            case DisconnectCause.CS_RESTRICTED:
                return R.string.callFailed_dsac_restricted;
            case DisconnectCause.CS_RESTRICTED_EMERGENCY:
                return R.string.callFailed_dsac_restricted_emergency;
            case DisconnectCause.CS_RESTRICTED_NORMAL:
                return R.string.callFailed_dsac_restricted_normal;
            case DisconnectCause.OUTGOING_FAILURE:
            case DisconnectCause.OUTGOING_CANCELED:
                // We couldn't successfully place the call; there was some
                // failure in the telephony layer.
                // TODO: Need UI spec for this failure case; for now just
                // show a generic error.
                return R.string.incall_error_call_failed;
            case DisconnectCause.POWER_OFF:
                // Radio is explictly powered off, presumably because the
                // device is in airplane mode.
                //
                // TODO: For now this UI is ultra-simple: we simply display
                // a message telling the user to turn off airplane mode.
                // But it might be nicer for the dialog to offer the option
                // to turn the radio on right there (and automatically retry
                // the call once network registration is complete.)
                return R.string.incall_error_power_off;
            case DisconnectCause.EMERGENCY_ONLY:
                // Only emergency numbers are allowed, but we tried to dial
                // a non-emergency number.
                return R.string.incall_error_emergency_only;
            case DisconnectCause.OUT_OF_SERVICE:
                // No network connection.
                return R.string.incall_error_out_of_service;
            case DisconnectCause.NO_PHONE_NUMBER_SUPPLIED:
                // The supplied Intent didn't contain a valid phone number.
                // (This is rare and should only ever happen with broken
                // 3rd-party apps.) For now just show a generic error.
                return R.string.incall_error_no_phone_number_supplied;
            case DisconnectCause.VOICEMAIL_NUMBER_MISSING:
                // TODO: Need to bring up the "Missing Voicemail Number" dialog, which
                // will ultimately take us to the Call Settings.
                return R.string.incall_error_missing_voicemail_number;
            case DisconnectCause.DIALED_MMI:
                // TODO: Implement MMI; see MMIDialogActivity in packages/services/Telephony
                return INVALID_RES_ID;
            default:
                return INVALID_RES_ID;
        }
    }

    private void onDialogDismissed() {
        mDialog = null;
        InCallPresenter.getInstance().onDismissDialog();
    }
}
