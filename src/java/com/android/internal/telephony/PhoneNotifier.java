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

package com.android.internal.telephony;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.telephony.Annotation.DataFailureCause;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.Annotation.SrvccState;
import android.telephony.BarringInfo;
import android.telephony.CallQuality;
import android.telephony.CellIdentity;
import android.telephony.CellInfo;
import android.telephony.PhoneCapability;
import android.telephony.PreciseDataConnectionState;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;

import java.util.List;

/**
 * {@hide}
 */
public interface PhoneNotifier {

    void notifyPhoneState(Phone sender);

    void notifyServiceState(Phone sender);

    /**
     * Notify registrants of the current CellLocation.
     *
     * <p>Use CellIdentity that is Parcellable to pass AIDL; convert to CellLocation in client code.
     */
    void notifyCellLocation(Phone sender, CellIdentity cellIdentity);

    @UnsupportedAppUsage
    void notifySignalStrength(Phone sender);

    @UnsupportedAppUsage
    void notifyMessageWaitingChanged(Phone sender);

    void notifyCallForwardingChanged(Phone sender);

    /** Send a notification that the Data Connection for a particular apnType has changed */
    void notifyDataConnection(
            Phone sender, String apnType, PreciseDataConnectionState preciseState);

    void notifyDataActivity(Phone sender);

    void notifyCellInfo(Phone sender, List<CellInfo> cellInfo);

    void notifyPreciseCallState(Phone sender);

    void notifyDisconnectCause(Phone sender, int cause, int preciseCause);

    void notifyImsDisconnectCause(Phone sender, ImsReasonInfo imsReasonInfo);

    /** Send a notification that a particular data connection has failed with specified cause. */
    void notifyDataConnectionFailed(Phone sender, String apnType, String apn,
                                                  @DataFailureCause int failCause);

    /** Send a notification that the SRVCC state has changed.*/
    void notifySrvccStateChanged(Phone sender, @SrvccState int state);

    /** Send a notification that the voice activation state has changed */
    void notifyVoiceActivationStateChanged(Phone sender, int activationState);

    /** Send a notification that the data activation state has changed */
    void notifyDataActivationStateChanged(Phone sender, int activationState);

    /** Send a notification that the users mobile data setting has changed */
    void notifyUserMobileDataStateChanged(Phone sender, boolean state);

    /** Send a notification that the phone capability has changed */
    void notifyPhoneCapabilityChanged(PhoneCapability capability);

    void notifyRadioPowerStateChanged(Phone sender, @RadioPowerState int state);

    /** Notify of change to EmergencyNumberList. */
    void notifyEmergencyNumberList(Phone sender);

    /** Notify of a change for Outgoing Emergency Call. */
    void notifyOutgoingEmergencyCall(Phone sender, EmergencyNumber emergencyNumber);

    /** Notify of a change for Outgoing Emergency Sms. */
    void notifyOutgoingEmergencySms(Phone sender, EmergencyNumber emergencyNumber);

    /** Notify of a change to the call quality of an active foreground call. */
    void notifyCallQualityChanged(Phone sender, CallQuality callQuality, int callNetworkType);

    /** Notify registration failed */
    void notifyRegistrationFailed(Phone sender, @NonNull CellIdentity cellIdentity,
            @NonNull String chosenPlmn, int domain, int causeCode, int additionalCauseCode);

    /** Notify barring info has changed */
    void notifyBarringInfoChanged(Phone sender, @NonNull BarringInfo barringInfo);
}
