/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.WifiManager.EXTRA_SCAN_AVAILABLE;
import static android.net.wifi.WifiManager.WIFI_SCAN_AVAILABLE;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ScanOnlyModeManager}.
 */
@SmallTest
public class ScanOnlyModeManagerTest {
    private static final String TAG = "ScanOnlyModeManagerTest";
    private static final String TEST_INTERFACE_NAME = "testif0";
    private static final String OTHER_INTERFACE_NAME = "notTestIf";

    TestLooper mLooper;

    ScanOnlyModeManager mScanOnlyModeManager;

    @Mock Context mContext;
    @Mock WifiMetrics mWifiMetrics;
    @Mock WifiNative mWifiNative;
    @Mock ScanOnlyModeManager.Listener mListener;
    @Mock WifiMonitor mWifiMonitor;
    @Mock ScanRequestProxy mScanRequestProxy;

    final ArgumentCaptor<WifiNative.StatusListener> mStatusListenerCaptor =
            ArgumentCaptor.forClass(WifiNative.StatusListener.class);
    final ArgumentCaptor<WifiNative.InterfaceCallback> mInterfaceCallbackCaptor =
            ArgumentCaptor.forClass(WifiNative.InterfaceCallback.class);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();

        mScanOnlyModeManager = createScanOnlyModeManager();
        mLooper.dispatchAll();
    }

    private ScanOnlyModeManager createScanOnlyModeManager() {
        return new ScanOnlyModeManager(mContext, mLooper.getLooper(), mWifiNative, mListener,
                mWifiMetrics, mScanRequestProxy);
    }

    private void startScanOnlyModeAndVerifyEnabled() throws Exception {
        when(mWifiNative.setupInterfaceForClientMode(any())).thenReturn(TEST_INTERFACE_NAME);
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();

        verify(mWifiNative).registerStatusListener(mStatusListenerCaptor.capture());
        verify(mWifiNative).setupInterfaceForClientMode(mInterfaceCallbackCaptor.capture());

        // now mark the interface as up
        mInterfaceCallbackCaptor.getValue().onUp(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_ENABLED);
        checkWifiStateChangeListenerUpdate(WIFI_STATE_ENABLED);
        verify(mScanRequestProxy, atLeastOnce()).enableScanningForHiddenNetworks(false);
    }

    private void checkWifiScanStateChangedBroadcast(Intent intent, int expectedCurrentState) {
        String action = intent.getAction();
        assertEquals(WIFI_SCAN_AVAILABLE, action);
        int currentState = intent.getIntExtra(EXTRA_SCAN_AVAILABLE, WIFI_STATE_UNKNOWN);
        assertEquals(expectedCurrentState, currentState);
    }

    private void checkWifiStateChangeListenerUpdate(int expectedCurrentState) {
        verify(mListener).onStateChanged(eq(expectedCurrentState));
    }

    /**
     * ScanMode start sets up an interface in ClientMode for scanning.
     */
    @Test
    public void scanModeStartCreatesClientInterface() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
    }

    /**
     * ScanMode increments failure metrics when failing to setup client mode.
     */
    @Test
    public void detectAndReportErrorWhenSetupForClientWifiNativeFailure() throws Exception {
        when(mWifiNative.setupInterfaceForClientMode(any())).thenReturn(null);
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
        checkWifiStateChangeListenerUpdate(WIFI_STATE_UNKNOWN);
    }

    /**
     * ScanMode start does not indicate scanning is available when the interface name is empty.
     */
    @Test
    public void scanModeStartDoesNotSendScanningActiveWhenClientInterfaceNameIsEmpty()
            throws Exception {
        when(mWifiNative.setupInterfaceForClientMode(any())).thenReturn("");
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
        checkWifiStateChangeListenerUpdate(WIFI_STATE_UNKNOWN);
    }

    /**
     * Calling ScanOnlyModeManager.start twice does not crash or restart scan mode.
     */
    @Test
    public void scanOnlyModeStartCalledTwice() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        reset(mWifiNative, mContext);
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();
        verifyNoMoreInteractions(mWifiNative, mContext);
    }

    /**
     * ScanMode stop properly cleans up state
     */
    @Test
    public void scanModeStopCleansUpState() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        reset(mContext);
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
        verify(mWifiNative).teardownInterface(TEST_INTERFACE_NAME);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
        checkWifiStateChangeListenerUpdate(WIFI_STATE_DISABLED);
        verify(mScanRequestProxy).clearScanResults();
    }

    /**
     * Calling stop when ScanMode is not started should not send scan state updates
     */
    @Test
    public void scanModeStopWhenNotStartedDoesNotUpdateScanStateUpdates() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
        reset(mContext, mListener);

        // now call stop again
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());
        verify(mListener, never()).onStateChanged(anyInt());
    }

    /**
     * Triggering interface down when ScanOnlyMode is active properly exits the active state.
     */
    @Test
    public void scanModeStartedStopsWhenInterfaceDown() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        reset(mContext);
        mInterfaceCallbackCaptor.getValue().onDown(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(), eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
        checkWifiStateChangeListenerUpdate(WIFI_STATE_DISABLED);
    }

    /**
     * Testing the handling of a wifinative failure status change notification.
     */
    @Test
    public void scanModeStartedStopsOnNativeFailure() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        reset(mContext);
        mStatusListenerCaptor.getValue().onStatusChanged(false);
        mLooper.dispatchAll();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(), eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
        checkWifiStateChangeListenerUpdate(WIFI_STATE_DISABLED);
    }
}
