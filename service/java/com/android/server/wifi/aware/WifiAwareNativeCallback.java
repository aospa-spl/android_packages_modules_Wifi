/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi.aware;

import android.hardware.wifi.V1_0.NanClusterEventInd;
import android.hardware.wifi.V1_0.NanClusterEventType;
import android.hardware.wifi.V1_0.NanDataPathConfirmInd;
import android.hardware.wifi.V1_0.NanDataPathRequestInd;
import android.hardware.wifi.V1_0.NanFollowupReceivedInd;
import android.hardware.wifi.V1_0.NanMatchInd;
import android.hardware.wifi.V1_0.NanStatusType;
import android.hardware.wifi.V1_0.WifiNanStatus;
import android.hardware.wifi.V1_2.NanDataPathScheduleUpdateInd;
import android.hardware.wifi.V1_6.IWifiNanIfaceEventCallback;
import android.hardware.wifi.V1_6.NanCipherSuiteType;
import android.hardware.wifi.V1_6.WifiChannelWidthInMhz;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiAnnotations;
import android.net.wifi.aware.Characteristics;
import android.net.wifi.aware.WifiAwareChannelInfo;
import android.net.wifi.util.HexEncoding;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.modules.utils.BasicShellCommandHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages the callbacks from Wi-Fi Aware HIDL (HAL).
 */
public class WifiAwareNativeCallback extends IWifiNanIfaceEventCallback.Stub implements
        WifiAwareShellCommand.DelegatedShellCommand {
    private static final String TAG = "WifiAwareNativeCallback";
    private boolean mDbg = false;

    /* package */ boolean mIsHal12OrLater = false;
    /* package */ boolean mIsHal15OrLater = false;
    /* package */ boolean mIsHal16OrLater = false;

    private final WifiAwareStateManager mWifiAwareStateManager;

    public WifiAwareNativeCallback(WifiAwareStateManager wifiAwareStateManager) {
        mWifiAwareStateManager = wifiAwareStateManager;
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(boolean verbose) {
        mDbg = verbose;
    }


    /*
     * Counts of callbacks from HAL. Retrievable through shell command.
     */
    private static final int CB_EV_CLUSTER = 0;
    private static final int CB_EV_DISABLED = 1;
    private static final int CB_EV_PUBLISH_TERMINATED = 2;
    private static final int CB_EV_SUBSCRIBE_TERMINATED = 3;
    private static final int CB_EV_MATCH = 4;
    private static final int CB_EV_MATCH_EXPIRED = 5;
    private static final int CB_EV_FOLLOWUP_RECEIVED = 6;
    private static final int CB_EV_TRANSMIT_FOLLOWUP = 7;
    private static final int CB_EV_DATA_PATH_REQUEST = 8;
    private static final int CB_EV_DATA_PATH_CONFIRM = 9;
    private static final int CB_EV_DATA_PATH_TERMINATED = 10;
    private static final int CB_EV_DATA_PATH_SCHED_UPDATE = 11;

    private SparseIntArray mCallbackCounter = new SparseIntArray();
    private SparseArray<List<WifiAwareChannelInfo>> mChannelInfoPerNdp = new SparseArray<>();

    private void incrementCbCount(int callbackId) {
        mCallbackCounter.put(callbackId, mCallbackCounter.get(callbackId) + 1);
    }

    /**
     * Interpreter of adb shell command 'adb shell cmd wifiaware native_cb ...'.
     *
     * @return -1 if parameter not recognized or invalid value, 0 otherwise.
     */
    @Override
    public int onCommand(BasicShellCommandHandler parentShell) {
        final PrintWriter pwe = parentShell.getErrPrintWriter();
        final PrintWriter pwo = parentShell.getOutPrintWriter();

        String subCmd = parentShell.getNextArgRequired();
        switch (subCmd) {
            case "get_cb_count": {
                String option = parentShell.getNextOption();
                boolean reset = false;
                if (option != null) {
                    if ("--reset".equals(option)) {
                        reset = true;
                    } else {
                        pwe.println("Unknown option to 'get_cb_count'");
                        return -1;
                    }
                }

                JSONObject j = new JSONObject();
                try {
                    for (int i = 0; i < mCallbackCounter.size(); ++i) {
                        j.put(Integer.toString(mCallbackCounter.keyAt(i)),
                                mCallbackCounter.valueAt(i));
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "onCommand: get_cb_count e=" + e);
                }
                pwo.println(j.toString());
                if (reset) {
                    mCallbackCounter.clear();
                }
                return 0;
            }
            case  "get_channel_info": {
                String option = parentShell.getNextOption();
                if (option != null) {
                    pwe.println("Unknown option to 'get_channel_info'");
                    return -1;
                }
                String channelInfoString = convertChannelInfoToJsonString();
                pwo.println(channelInfoString);
                return 0;
            }
            default:
                pwe.println("Unknown 'wifiaware native_cb <cmd>'");
        }

        return -1;
    }

    @Override
    public void onReset() {
        // NOP (onReset is intended for configuration reset - not data reset)
    }

    @Override
    public void onHelp(String command, BasicShellCommandHandler parentShell) {
        final PrintWriter pw = parentShell.getOutPrintWriter();

        pw.println("  " + command);
        pw.println("    get_cb_count [--reset]: gets the number of callbacks (and optionally reset "
                + "count)");
        pw.println("    get_channel_info: prints out existing NDP channel info as a JSON String");
    }

    @Override
    public void notifyCapabilitiesResponse(short id, WifiNanStatus status,
            android.hardware.wifi.V1_0.NanCapabilities capabilities) {
        if (mDbg) {
            Log.v(TAG, "notifyCapabilitiesResponse: id=" + id + ", status=" + statusString(status)
                    + ", capabilities=" + capabilities);
        }

        if (mIsHal15OrLater) {
            Log.wtf(TAG, "notifyCapabilitiesResponse should not be called by a >=1.5 HAL!");
        }

        if (status.status == NanStatusType.SUCCESS) {
            Capabilities frameworkCapabilities = toFrameworkCapability10(capabilities);

            mWifiAwareStateManager.onCapabilitiesUpdateResponse(id, frameworkCapabilities);
        } else {
            Log.e(TAG, "notifyCapabilitiesResponse: error code=" + status.status + " ("
                    + status.description + ")");
        }
    }

    @Override
    public void notifyCapabilitiesResponse_1_5(short id, WifiNanStatus status,
            android.hardware.wifi.V1_5.NanCapabilities capabilities) throws RemoteException {
        if (mDbg) {
            Log.v(TAG, "notifyCapabilitiesResponse_1_5: id=" + id + ", status="
                    + statusString(status) + ", capabilities=" + capabilities);
        }

        if (!mIsHal15OrLater) {
            Log.wtf(TAG, "notifyCapabilitiesResponse_1_5 should not be called by a <1.5 HAL!");
            return;
        }

        if (status.status == NanStatusType.SUCCESS) {
            Capabilities frameworkCapabilities = toFrameworkCapability10(capabilities.V1_0);
            frameworkCapabilities.isInstantCommunicationModeSupported =
                    capabilities.instantCommunicationModeSupportFlag;

            mWifiAwareStateManager.onCapabilitiesUpdateResponse(id, frameworkCapabilities);
        } else {
            Log.e(TAG, "notifyCapabilitiesResponse_1_5: error code=" + status.status + " ("
                    + status.description + ")");
        }
    }

    @Override
    public void notifyCapabilitiesResponse_1_6(short id, WifiNanStatus status,
            android.hardware.wifi.V1_6.NanCapabilities capabilities) throws RemoteException {
        if (mDbg) {
            Log.v(TAG, "notifyCapabilitiesResponse_1_6: id=" + id + ", status="
                    + statusString(status) + ", capabilities=" + capabilities);
        }

        if (!mIsHal16OrLater) {
            Log.wtf(TAG, "notifyCapabilitiesResponse_1_6 should not be called by a <1.6 HAL!");
            return;
        }

        if (status.status == NanStatusType.SUCCESS) {
            Capabilities frameworkCapabilities = toFrameworkCapability1_6(capabilities);

            mWifiAwareStateManager.onCapabilitiesUpdateResponse(id, frameworkCapabilities);
        } else {
            Log.e(TAG, "notifyCapabilitiesResponse_1_6: error code=" + status.status + " ("
                    + status.description + ")");
        }
    }

    private Capabilities toFrameworkCapability1_6(
            android.hardware.wifi.V1_6.NanCapabilities capabilities) {
        Capabilities frameworkCapabilities = new Capabilities();
        frameworkCapabilities.maxConcurrentAwareClusters = capabilities.maxConcurrentClusters;
        frameworkCapabilities.maxPublishes = capabilities.maxPublishes;
        frameworkCapabilities.maxSubscribes = capabilities.maxSubscribes;
        frameworkCapabilities.maxServiceNameLen = capabilities.maxServiceNameLen;
        frameworkCapabilities.maxMatchFilterLen = capabilities.maxMatchFilterLen;
        frameworkCapabilities.maxTotalMatchFilterLen = capabilities.maxTotalMatchFilterLen;
        frameworkCapabilities.maxServiceSpecificInfoLen =
                capabilities.maxServiceSpecificInfoLen;
        frameworkCapabilities.maxExtendedServiceSpecificInfoLen =
                capabilities.maxExtendedServiceSpecificInfoLen;
        frameworkCapabilities.maxNdiInterfaces = capabilities.maxNdiInterfaces;
        frameworkCapabilities.maxNdpSessions = capabilities.maxNdpSessions;
        frameworkCapabilities.maxAppInfoLen = capabilities.maxAppInfoLen;
        frameworkCapabilities.maxQueuedTransmitMessages =
                capabilities.maxQueuedTransmitFollowupMsgs;
        frameworkCapabilities.maxSubscribeInterfaceAddresses =
                capabilities.maxSubscribeInterfaceAddresses;
        frameworkCapabilities.supportedCipherSuites = toPublicCipherSuites(
                capabilities.supportedCipherSuites);
        frameworkCapabilities.isInstantCommunicationModeSupported =
                capabilities.instantCommunicationModeSupportFlag;
        return frameworkCapabilities;
    }

    private Capabilities toFrameworkCapability10(
            android.hardware.wifi.V1_0.NanCapabilities capabilities) {
        Capabilities frameworkCapabilities = new Capabilities();
        frameworkCapabilities.maxConcurrentAwareClusters = capabilities.maxConcurrentClusters;
        frameworkCapabilities.maxPublishes = capabilities.maxPublishes;
        frameworkCapabilities.maxSubscribes = capabilities.maxSubscribes;
        frameworkCapabilities.maxServiceNameLen = capabilities.maxServiceNameLen;
        frameworkCapabilities.maxMatchFilterLen = capabilities.maxMatchFilterLen;
        frameworkCapabilities.maxTotalMatchFilterLen = capabilities.maxTotalMatchFilterLen;
        frameworkCapabilities.maxServiceSpecificInfoLen =
                capabilities.maxServiceSpecificInfoLen;
        frameworkCapabilities.maxExtendedServiceSpecificInfoLen =
                capabilities.maxExtendedServiceSpecificInfoLen;
        frameworkCapabilities.maxNdiInterfaces = capabilities.maxNdiInterfaces;
        frameworkCapabilities.maxNdpSessions = capabilities.maxNdpSessions;
        frameworkCapabilities.maxAppInfoLen = capabilities.maxAppInfoLen;
        frameworkCapabilities.maxQueuedTransmitMessages =
                capabilities.maxQueuedTransmitFollowupMsgs;
        frameworkCapabilities.maxSubscribeInterfaceAddresses =
                capabilities.maxSubscribeInterfaceAddresses;
        frameworkCapabilities.supportedCipherSuites = toPublicCipherSuites(
                capabilities.supportedCipherSuites);
        frameworkCapabilities.isInstantCommunicationModeSupported = false;
        return frameworkCapabilities;
    }

    private int toPublicCipherSuites(int nativeCipherSuites) {
        int publicCipherSuites = 0;

        if ((nativeCipherSuites & NanCipherSuiteType.SHARED_KEY_128_MASK) != 0) {
            publicCipherSuites |= Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128;
        }
        if ((nativeCipherSuites & NanCipherSuiteType.SHARED_KEY_256_MASK) != 0) {
            publicCipherSuites |= Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256;
        }
        if ((nativeCipherSuites & NanCipherSuiteType.PUBLIC_KEY_128_MASK) != 0) {
            publicCipherSuites |= Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_PK_128;
        }
        if ((nativeCipherSuites & NanCipherSuiteType.PUBLIC_KEY_256_MASK) != 0) {
            publicCipherSuites |= Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_PK_256;
        }

        return publicCipherSuites;
    }

    @Override
    public void notifyEnableResponse(short id, WifiNanStatus status) {
        if (mDbg) Log.v(TAG, "notifyEnableResponse: id=" + id + ", status=" + statusString(status));

        if (status.status == NanStatusType.ALREADY_ENABLED) {
            Log.wtf(TAG, "notifyEnableResponse: id=" + id + ", already enabled!?");
        }

        if (status.status == NanStatusType.SUCCESS
                || status.status == NanStatusType.ALREADY_ENABLED) {
            mWifiAwareStateManager.onConfigSuccessResponse(id);
        } else {
            mWifiAwareStateManager.onConfigFailedResponse(id, status.status);
        }
    }

    @Override
    public void notifyConfigResponse(short id, WifiNanStatus status) {
        if (mDbg) Log.v(TAG, "notifyConfigResponse: id=" + id + ", status=" + statusString(status));

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onConfigSuccessResponse(id);
        } else {
            mWifiAwareStateManager.onConfigFailedResponse(id, status.status);
        }
    }

    @Override
    public void notifyDisableResponse(short id, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "notifyDisableResponse: id=" + id + ", status=" + statusString(status));
        }

        if (status.status != NanStatusType.SUCCESS) {
            Log.e(TAG, "notifyDisableResponse: failure - code=" + status.status + " ("
                    + status.description + ")");
        }
        mWifiAwareStateManager.onDisableResponse(id, status.status);
    }

    @Override
    public void notifyStartPublishResponse(short id, WifiNanStatus status, byte publishId) {
        if (mDbg) {
            Log.v(TAG, "notifyStartPublishResponse: id=" + id + ", status=" + statusString(status)
                    + ", publishId=" + publishId);
        }

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onSessionConfigSuccessResponse(id, true, publishId);
        } else {
            mWifiAwareStateManager.onSessionConfigFailResponse(id, true, status.status);
        }
    }

    @Override
    public void notifyStopPublishResponse(short id, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "notifyStopPublishResponse: id=" + id + ", status=" + statusString(status));
        }

        if (status.status == NanStatusType.SUCCESS) {
            // NOP
        } else {
            Log.e(TAG, "notifyStopPublishResponse: failure - code=" + status.status + " ("
                    + status.description + ")");
        }
    }

    @Override
    public void notifyStartSubscribeResponse(short id, WifiNanStatus status, byte subscribeId) {
        if (mDbg) {
            Log.v(TAG, "notifyStartSubscribeResponse: id=" + id + ", status=" + statusString(status)
                    + ", subscribeId=" + subscribeId);
        }

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onSessionConfigSuccessResponse(id, false, subscribeId);
        } else {
            mWifiAwareStateManager.onSessionConfigFailResponse(id, false, status.status);
        }
    }

    @Override
    public void notifyStopSubscribeResponse(short id, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "notifyStopSubscribeResponse: id=" + id + ", status="
                    + statusString(status));
        }

        if (status.status == NanStatusType.SUCCESS) {
            // NOP
        } else {
            Log.e(TAG, "notifyStopSubscribeResponse: failure - code=" + status.status + " ("
                    + status.description + ")");
        }
    }

    @Override
    public void notifyTransmitFollowupResponse(short id, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "notifyTransmitFollowupResponse: id=" + id + ", status="
                    + statusString(status));
        }

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onMessageSendQueuedSuccessResponse(id);
        } else {
            mWifiAwareStateManager.onMessageSendQueuedFailResponse(id, status.status);
        }
    }

    @Override
    public void notifyCreateDataInterfaceResponse(short id, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "notifyCreateDataInterfaceResponse: id=" + id + ", status="
                    + statusString(status));
        }

        mWifiAwareStateManager.onCreateDataPathInterfaceResponse(id,
                status.status == NanStatusType.SUCCESS, status.status);
    }

    @Override
    public void notifyDeleteDataInterfaceResponse(short id, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "notifyDeleteDataInterfaceResponse: id=" + id + ", status="
                    + statusString(status));
        }

        mWifiAwareStateManager.onDeleteDataPathInterfaceResponse(id,
                status.status == NanStatusType.SUCCESS, status.status);
    }

    @Override
    public void notifyInitiateDataPathResponse(short id, WifiNanStatus status,
            int ndpInstanceId) {
        if (mDbg) {
            Log.v(TAG, "notifyInitiateDataPathResponse: id=" + id + ", status="
                    + statusString(status) + ", ndpInstanceId=" + ndpInstanceId);
        }

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onInitiateDataPathResponseSuccess(id, ndpInstanceId);
        } else {
            mWifiAwareStateManager.onInitiateDataPathResponseFail(id, status.status);
        }
    }

    @Override
    public void notifyRespondToDataPathIndicationResponse(short id, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "notifyRespondToDataPathIndicationResponse: id=" + id
                    + ", status=" + statusString(status));
        }

        mWifiAwareStateManager.onRespondToDataPathSetupRequestResponse(id,
                status.status == NanStatusType.SUCCESS, status.status);
    }

    @Override
    public void notifyTerminateDataPathResponse(short id, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "notifyTerminateDataPathResponse: id=" + id + ", status="
                    + statusString(status));
        }

        mWifiAwareStateManager.onEndDataPathResponse(id, status.status == NanStatusType.SUCCESS,
                status.status);
    }

    @Override
    public void eventClusterEvent(NanClusterEventInd event) {
        if (mDbg) {
            Log.v(TAG, "eventClusterEvent: eventType=" + event.eventType + ", addr="
                    + String.valueOf(HexEncoding.encode(event.addr)));
        }
        incrementCbCount(CB_EV_CLUSTER);

        if (event.eventType == NanClusterEventType.DISCOVERY_MAC_ADDRESS_CHANGED) {
            mWifiAwareStateManager.onInterfaceAddressChangeNotification(event.addr);
        } else if (event.eventType == NanClusterEventType.STARTED_CLUSTER) {
            mWifiAwareStateManager.onClusterChangeNotification(
                    WifiAwareClientState.CLUSTER_CHANGE_EVENT_STARTED, event.addr);
        } else if (event.eventType == NanClusterEventType.JOINED_CLUSTER) {
            mWifiAwareStateManager.onClusterChangeNotification(
                    WifiAwareClientState.CLUSTER_CHANGE_EVENT_JOINED, event.addr);
        } else {
            Log.e(TAG, "eventClusterEvent: invalid eventType=" + event.eventType);
        }
    }

    @Override
    public void eventDisabled(WifiNanStatus status) {
        if (mDbg) Log.v(TAG, "eventDisabled: status=" + statusString(status));
        incrementCbCount(CB_EV_DISABLED);

        mWifiAwareStateManager.onAwareDownNotification(status.status);
    }

    @Override
    public void eventPublishTerminated(byte sessionId, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "eventPublishTerminated: sessionId=" + sessionId + ", status="
                    + statusString(status));
        }
        incrementCbCount(CB_EV_PUBLISH_TERMINATED);

        mWifiAwareStateManager.onSessionTerminatedNotification(sessionId, status.status, true);
    }

    @Override
    public void eventSubscribeTerminated(byte sessionId, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "eventSubscribeTerminated: sessionId=" + sessionId + ", status="
                    + statusString(status));
        }
        incrementCbCount(CB_EV_SUBSCRIBE_TERMINATED);

        mWifiAwareStateManager.onSessionTerminatedNotification(sessionId, status.status, false);
    }

    @Override
    public void eventMatch(NanMatchInd event) {
        if (mDbg) {
            Log.v(TAG, "eventMatch: discoverySessionId=" + event.discoverySessionId + ", peerId="
                    + event.peerId + ", addr=" + String.valueOf(HexEncoding.encode(event.addr))
                    + ", serviceSpecificInfo=" + Arrays.toString(
                    convertArrayListToNativeByteArray(event.serviceSpecificInfo)) + ", ssi.size()="
                    + (event.serviceSpecificInfo == null ? 0 : event.serviceSpecificInfo.size())
                    + ", matchFilter=" + Arrays.toString(
                    convertArrayListToNativeByteArray(event.matchFilter)) + ", mf.size()=" + (
                    event.matchFilter == null ? 0 : event.matchFilter.size())
                    + ", rangingIndicationType=" + event.rangingIndicationType
                    + ", rangingMeasurementInCm=" + event.rangingMeasurementInCm);
        }
        incrementCbCount(CB_EV_MATCH);

        // TODO: b/69428593 get rid of conversion once HAL moves from CM to MM
        mWifiAwareStateManager.onMatchNotification(event.discoverySessionId, event.peerId,
                event.addr, convertArrayListToNativeByteArray(event.serviceSpecificInfo),
                convertArrayListToNativeByteArray(event.matchFilter), event.rangingIndicationType,
                event.rangingMeasurementInCm * 10, new byte[0], 0);
    }

    @Override
    public void eventMatch_1_6(android.hardware.wifi.V1_6.NanMatchInd event) {
        if (mDbg) {
            Log.v(TAG, "eventMatch_1_6: discoverySessionId=" + event.discoverySessionId
                    + ", peerId=" + event.peerId
                    + ", addr=" + String.valueOf(HexEncoding.encode(event.addr))
                    + ", serviceSpecificInfo=" + Arrays.toString(
                    convertArrayListToNativeByteArray(event.serviceSpecificInfo)) + ", ssi.size()="
                    + (event.serviceSpecificInfo == null ? 0 : event.serviceSpecificInfo.size())
                    + ", matchFilter=" + Arrays.toString(
                    convertArrayListToNativeByteArray(event.matchFilter)) + ", mf.size()=" + (
                    event.matchFilter == null ? 0 : event.matchFilter.size())
                    + ", rangingIndicationType=" + event.rangingIndicationType
                    + ", rangingMeasurementInCm=" + event.rangingMeasurementInMm + ", "
                    + "scid=" + Arrays.toString(convertArrayListToNativeByteArray(event.scid)));
        }
        incrementCbCount(CB_EV_MATCH);

        // TODO: b/69428593 get rid of conversion once HAL moves from CM to MM
        mWifiAwareStateManager.onMatchNotification(event.discoverySessionId, event.peerId,
                event.addr, convertArrayListToNativeByteArray(event.serviceSpecificInfo),
                convertArrayListToNativeByteArray(event.matchFilter), event.rangingIndicationType,
                event.rangingMeasurementInMm,
                convertArrayListToNativeByteArray(event.scid),
                toPublicCipherSuites(event.peerCipherType));
    }

    @Override
    public void eventMatchExpired(byte discoverySessionId, int peerId) {
        if (mDbg) {
            Log.v(TAG, "eventMatchExpired: discoverySessionId=" + discoverySessionId
                    + ", peerId=" + peerId);
        }
        incrementCbCount(CB_EV_MATCH_EXPIRED);
        mWifiAwareStateManager.onMatchExpiredNotification(discoverySessionId, peerId);
    }

    @Override
    public void eventFollowupReceived(NanFollowupReceivedInd event) {
        if (mDbg) {
            Log.v(TAG, "eventFollowupReceived: discoverySessionId=" + event.discoverySessionId
                    + ", peerId=" + event.peerId + ", addr=" + String.valueOf(
                    HexEncoding.encode(event.addr)) + ", serviceSpecificInfo=" + Arrays.toString(
                    convertArrayListToNativeByteArray(event.serviceSpecificInfo)) + ", ssi.size()="
                    + (event.serviceSpecificInfo == null ? 0 : event.serviceSpecificInfo.size()));
        }
        incrementCbCount(CB_EV_FOLLOWUP_RECEIVED);

        mWifiAwareStateManager.onMessageReceivedNotification(event.discoverySessionId, event.peerId,
                event.addr, convertArrayListToNativeByteArray(event.serviceSpecificInfo));
    }

    @Override
    public void eventTransmitFollowup(short id, WifiNanStatus status) {
        if (mDbg) {
            Log.v(TAG, "eventTransmitFollowup: id=" + id + ", status=" + statusString(status));
        }
        incrementCbCount(CB_EV_TRANSMIT_FOLLOWUP);

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onMessageSendSuccessNotification(id);
        } else {
            mWifiAwareStateManager.onMessageSendFailNotification(id, status.status);
        }
    }

    @Override
    public void eventDataPathRequest(NanDataPathRequestInd event) {
        if (mDbg) {
            Log.v(TAG, "eventDataPathRequest: discoverySessionId=" + event.discoverySessionId
                    + ", peerDiscMacAddr=" + String.valueOf(
                    HexEncoding.encode(event.peerDiscMacAddr)) + ", ndpInstanceId="
                    + event.ndpInstanceId + ", appInfo.size()=" + event.appInfo.size());
        }
        incrementCbCount(CB_EV_DATA_PATH_REQUEST);

        mWifiAwareStateManager.onDataPathRequestNotification(event.discoverySessionId,
                event.peerDiscMacAddr, event.ndpInstanceId,
                convertArrayListToNativeByteArray(event.appInfo));
    }

    @Override
    public void eventDataPathConfirm(NanDataPathConfirmInd event) {
        if (mDbg) {
            Log.v(TAG, "onDataPathConfirm: ndpInstanceId=" + event.ndpInstanceId
                    + ", peerNdiMacAddr=" + String.valueOf(HexEncoding.encode(event.peerNdiMacAddr))
                    + ", dataPathSetupSuccess=" + event.dataPathSetupSuccess + ", reason="
                    + event.status.status + ", appInfo.size()=" + event.appInfo.size());
        }
        if (mIsHal12OrLater) {
            Log.wtf(TAG, "eventDataPathConfirm should not be called by a >=1.2 HAL!");
        }
        incrementCbCount(CB_EV_DATA_PATH_CONFIRM);

        mWifiAwareStateManager.onDataPathConfirmNotification(event.ndpInstanceId,
                event.peerNdiMacAddr, event.dataPathSetupSuccess, event.status.status,
                convertArrayListToNativeByteArray(event.appInfo), null);
    }

    @Override
    public void eventDataPathConfirm_1_2(android.hardware.wifi.V1_2.NanDataPathConfirmInd event) {
        if (mDbg) {
            Log.v(TAG, "eventDataPathConfirm_1_2: ndpInstanceId=" + event.V1_0.ndpInstanceId
                    + ", peerNdiMacAddr=" + String.valueOf(
                    HexEncoding.encode(event.V1_0.peerNdiMacAddr)) + ", dataPathSetupSuccess="
                    + event.V1_0.dataPathSetupSuccess + ", reason=" + event.V1_0.status.status
                    + ", appInfo.size()=" + event.V1_0.appInfo.size()
                    + ", channelInfo" + event.channelInfo);
        }
        if (!mIsHal12OrLater) {
            Log.wtf(TAG, "eventDataPathConfirm_1_2 should not be called by a <1.2 HAL!");
            return;
        }

        List<WifiAwareChannelInfo> wifiAwareChannelInfos =
                convertHalChannelInfo_1_2(event.channelInfo);
        incrementCbCount(CB_EV_DATA_PATH_CONFIRM);
        mChannelInfoPerNdp.put(event.V1_0.ndpInstanceId, wifiAwareChannelInfos);

        mWifiAwareStateManager.onDataPathConfirmNotification(event.V1_0.ndpInstanceId,
                event.V1_0.peerNdiMacAddr, event.V1_0.dataPathSetupSuccess,
                event.V1_0.status.status, convertArrayListToNativeByteArray(event.V1_0.appInfo),
                wifiAwareChannelInfos);
    }

    @Override
    public void eventDataPathConfirm_1_6(android.hardware.wifi.V1_6.NanDataPathConfirmInd event) {
        if (mDbg) {
            Log.v(TAG, "eventDataPathConfirm_1_6: ndpInstanceId=" + event.V1_0.ndpInstanceId
                    + ", peerNdiMacAddr=" + String.valueOf(
                    HexEncoding.encode(event.V1_0.peerNdiMacAddr)) + ", dataPathSetupSuccess="
                    + event.V1_0.dataPathSetupSuccess + ", reason=" + event.V1_0.status.status
                    + ", appInfo.size()=" + event.V1_0.appInfo.size()
                    + ", channelInfo" + event.channelInfo);
        }
        if (!mIsHal16OrLater) {
            Log.wtf(TAG, "eventDataPathConfirm_1_6 should not be called by a <1.6 HAL!");
            return;
        }

        List<WifiAwareChannelInfo> wifiAwareChannelInfos =
                convertHalChannelInfo_1_6(event.channelInfo);
        incrementCbCount(CB_EV_DATA_PATH_CONFIRM);
        mChannelInfoPerNdp.put(event.V1_0.ndpInstanceId, wifiAwareChannelInfos);

        mWifiAwareStateManager.onDataPathConfirmNotification(event.V1_0.ndpInstanceId,
                event.V1_0.peerNdiMacAddr, event.V1_0.dataPathSetupSuccess,
                event.V1_0.status.status, convertArrayListToNativeByteArray(event.V1_0.appInfo),
                wifiAwareChannelInfos);
    }

    @Override
    public void eventDataPathScheduleUpdate(NanDataPathScheduleUpdateInd event) {
        if (mDbg) {
            Log.v(TAG, "eventDataPathScheduleUpdate: peerMac="
                    + MacAddress.fromBytes(event.peerDiscoveryAddress).toString()
                    + ", ndpIds=" + event.ndpInstanceIds + ", channelInfo=" + event.channelInfo);
        }
        if (!mIsHal12OrLater) {
            Log.wtf(TAG, "eventDataPathScheduleUpdate should not be called by a <1.2 HAL!");
            return;
        }

        List<WifiAwareChannelInfo> wifiAwareChannelInfos =
                convertHalChannelInfo_1_2(event.channelInfo);
        incrementCbCount(CB_EV_DATA_PATH_SCHED_UPDATE);
        for (int ndpInstanceId : event.ndpInstanceIds) {
            mChannelInfoPerNdp.put(ndpInstanceId, wifiAwareChannelInfos);
        }

        mWifiAwareStateManager.onDataPathScheduleUpdateNotification(event.peerDiscoveryAddress,
                event.ndpInstanceIds, wifiAwareChannelInfos);
    }

    @Override
    public void eventDataPathScheduleUpdate_1_6(
            android.hardware.wifi.V1_6.NanDataPathScheduleUpdateInd event) {
        if (mDbg) {
            Log.v(TAG, "eventDataPathScheduleUpdate_1_6: peerMac="
                    + MacAddress.fromBytes(event.peerDiscoveryAddress).toString()
                    + ", ndpIds=" + event.ndpInstanceIds + ", channelInfo=" + event.channelInfo);
        }
        if (!mIsHal16OrLater) {
            Log.wtf(TAG, "eventDataPathScheduleUpdate_1_6 should not be called by a <1.6 HAL!");
            return;
        }

        List<WifiAwareChannelInfo> wifiAwareChannelInfos =
                convertHalChannelInfo_1_6(event.channelInfo);
        incrementCbCount(CB_EV_DATA_PATH_SCHED_UPDATE);
        for (int ndpInstanceId : event.ndpInstanceIds) {
            mChannelInfoPerNdp.put(ndpInstanceId, wifiAwareChannelInfos);
        }

        mWifiAwareStateManager.onDataPathScheduleUpdateNotification(event.peerDiscoveryAddress,
                event.ndpInstanceIds, wifiAwareChannelInfos);
    }

    @Override
    public void eventDataPathTerminated(int ndpInstanceId) {
        if (mDbg) Log.v(TAG, "eventDataPathTerminated: ndpInstanceId=" + ndpInstanceId);
        incrementCbCount(CB_EV_DATA_PATH_TERMINATED);
        mChannelInfoPerNdp.remove(ndpInstanceId);

        mWifiAwareStateManager.onDataPathEndNotification(ndpInstanceId);
    }

    /**
     * Reset the channel info when Aware is down.
     */
    /* package */ void resetChannelInfo() {
        mChannelInfoPerNdp.clear();
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiAwareNativeCallback:");
        pw.println("  mCallbackCounter: " + mCallbackCounter);
        pw.println("  mChannelInfoPerNdp: " + mChannelInfoPerNdp);
    }


    // utilities

    /**
     * Converts an ArrayList<Byte> to a byte[].
     *
     * @param from The input ArrayList<Byte></Byte> to convert from.
     *
     * @return A newly allocated byte[].
     */
    private byte[] convertArrayListToNativeByteArray(ArrayList<Byte> from) {
        if (from == null) {
            return null;
        }

        byte[] to = new byte[from.size()];
        for (int i = 0; i < from.size(); ++i) {
            to[i] = from.get(i);
        }
        return to;
    }

    private static String statusString(WifiNanStatus status) {
        if (status == null) {
            return "status=null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(status.status).append(" (").append(status.description).append(")");
        return sb.toString();
    }

    /**
     * Transfer the channel Info dict into a Json String which can be decoded by Json reader.
     * The Format is: "{ndpInstanceId: [{"channelFreq": channelFreq,
     * "channelBandwidth": channelBandwidth, "numSpatialStreams": numSpatialStreams}]}"
     * @return Json String.
     */
    private String convertChannelInfoToJsonString() {
        JSONObject channelInfoJson = new JSONObject();
        try {
            for (int i = 0; i < mChannelInfoPerNdp.size(); i++) {
                JSONArray infoJsonArray = new JSONArray();
                for (WifiAwareChannelInfo info : mChannelInfoPerNdp.valueAt(i)) {
                    JSONObject j = new JSONObject();
                    j.put("channelFreq", info.getChannelFrequencyMhz());
                    j.put("channelBandwidth", info.getChannelBandwidth());
                    j.put("numSpatialStreams", info.getSpatialStreamCount());
                    infoJsonArray.put(j);
                }
                channelInfoJson.put(Integer.toString(mChannelInfoPerNdp.keyAt(i)), infoJsonArray);
            }
        } catch (JSONException e) {
            Log.e(TAG, "onCommand: get_channel_info e=" + e);
        }
        return channelInfoJson.toString();
    }

    /**
     * Convert HAL channelBandwidth to framework enum
     */
    private @WifiAnnotations.ChannelWidth int getChannelBandwidthFromHal(int channelBandwidth) {
        switch(channelBandwidth) {
            case WifiChannelWidthInMhz.WIDTH_40:
                return ScanResult.CHANNEL_WIDTH_40MHZ;
            case WifiChannelWidthInMhz.WIDTH_80:
                return ScanResult.CHANNEL_WIDTH_80MHZ;
            case WifiChannelWidthInMhz.WIDTH_160:
                return ScanResult.CHANNEL_WIDTH_160MHZ;
            case WifiChannelWidthInMhz.WIDTH_80P80:
                return ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ;
            case WifiChannelWidthInMhz.WIDTH_320:
                return ScanResult.CHANNEL_WIDTH_320MHZ;
            default:
                return ScanResult.CHANNEL_WIDTH_20MHZ;
        }
    }
    /**
     * Convert HAL V1_2 NanDataPathChannelInfo to WifiAwareChannelInfo
     */
    private List<WifiAwareChannelInfo> convertHalChannelInfo_1_2(
            List<android.hardware.wifi.V1_2.NanDataPathChannelInfo> channelInfos) {
        List<WifiAwareChannelInfo> wifiAwareChannelInfos = new ArrayList<>();
        if (channelInfos == null) {
            return null;
        }
        for (android.hardware.wifi.V1_2.NanDataPathChannelInfo channelInfo : channelInfos) {
            wifiAwareChannelInfos.add(new WifiAwareChannelInfo(channelInfo.channelFreq,
                    getChannelBandwidthFromHal(channelInfo.channelBandwidth),
                    channelInfo.numSpatialStreams));
        }
        return wifiAwareChannelInfos;
    }
    /**
     * Convert HAL V1_6 NanDataPathChannelInfo to WifiAwareChannelInfo
     */
    private List<WifiAwareChannelInfo> convertHalChannelInfo_1_6(
            List<android.hardware.wifi.V1_6.NanDataPathChannelInfo> channelInfos) {
        List<WifiAwareChannelInfo> wifiAwareChannelInfos = new ArrayList<>();
        if (channelInfos == null) {
            return null;
        }
        for (android.hardware.wifi.V1_6.NanDataPathChannelInfo channelInfo : channelInfos) {
            wifiAwareChannelInfos.add(new WifiAwareChannelInfo(channelInfo.channelFreq,
                    getChannelBandwidthFromHal(channelInfo.channelBandwidth),
                    channelInfo.numSpatialStreams));
        }
        return wifiAwareChannelInfos;
    }
}
