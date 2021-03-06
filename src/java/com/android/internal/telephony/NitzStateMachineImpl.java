/*
 * Copyright 2017 The Android Open Source Project
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

import android.app.timedetector.TelephonyTimeSuggestion;
import android.content.Context;
import android.os.PowerManager;
import android.os.TimestampedValue;
import android.text.TextUtils;
import android.timezone.CountryTimeZones.OffsetResult;
import android.util.LocalLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.TimeZoneLookupHelper.CountryResult;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.util.IndentingPrintWriter;
import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * {@hide}
 */
public final class NitzStateMachineImpl implements NitzStateMachine {

    private static final String LOG_TAG = ServiceStateTracker.LOG_TAG;
    private static final boolean DBG = ServiceStateTracker.DBG;

    // Time detection state.

    /**
     * The last NITZ-sourced time considered sent to the time detector service. Used to rate-limit
     * calls to the time detector.
     */
    private TimestampedValue<Long> mSavedNitzTime;

    // Time Zone detection state.

    /** We always keep the last NITZ signal received in mLatestNitzSignal. */
    private TimestampedValue<NitzData> mLatestNitzSignal;

    /**
     * Records the country to use for time zone detection. It can be a valid ISO 3166 alpha-2 code
     * (lower case), empty (test network) or null (no country detected). A country code is required
     * to determine time zone except when on a test network.
     */
    private String mCountryIsoCode;

    /**
     * The last time zone ID that has been determined. It may not have been set as the device time
     * zone if automatic time zone detection is disabled but may later be used to set the time zone
     * if the user enables automatic time zone detection.
     */
    private String mSavedTimeZoneId;

    /**
     * The last time zone ID that was set. It is used for log entry filtering. This is different
     * from {@link #mSavedTimeZoneId} in that this records the last zone ID this class actually
     * suggested should be set as the device zone ID; i.e. it is only set if automatic time zone
     * detection is enabled.
     */
    private String mLastSetTimeZoneId;

    /**
     * Boolean is {@code true} if NITZ has been used to determine a time zone (which may not
     * ultimately have been used due to user settings). Cleared by {@link
     * #handleNetworkAvailable()}, {@link #handleCountryUnavailable()},
     * {@link #handleNetworkUnavailable()}, and {@link #handleAirplaneModeChanged(boolean)}. The
     * flag can be used when historic NITZ data may no longer be valid. {@code false} indicates it
     * is reasonable to try to set the time zone using less reliable algorithms than NITZ-based
     * detection such as by just using network country code.
     */
    private boolean mNitzTimeZoneDetectionSuccessful = false;

    // Miscellaneous dependencies and helpers not related to detection state.
    private final LocalLog mTimeLog = new LocalLog(30, false /* useLocalTimestamps */);
    private final LocalLog mTimeZoneLog = new LocalLog(30, false /* useLocalTimestamps */);
    private final Phone mPhone;
    private final DeviceState mDeviceState;
    private final TimeServiceHelper mTimeServiceHelper;
    private final TimeZoneLookupHelper mTimeZoneLookupHelper;
    /** Wake lock used while setting time of day. */
    private final PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "NitzStateMachine";

    public NitzStateMachineImpl(Phone phone) {
        this(phone,
                new TimeServiceHelperImpl(phone.getContext()),
                new DeviceStateImpl(phone),
                new TimeZoneLookupHelper());
    }

    @VisibleForTesting
    public NitzStateMachineImpl(Phone phone, TimeServiceHelper timeServiceHelper,
            DeviceState deviceState, TimeZoneLookupHelper timeZoneLookupHelper) {
        mPhone = phone;

        Context context = phone.getContext();
        PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        mDeviceState = deviceState;
        mTimeZoneLookupHelper = timeZoneLookupHelper;
        mTimeServiceHelper = timeServiceHelper;
        mTimeServiceHelper.setListener(new TimeServiceHelper.Listener() {
            @Override
            public void onTimeZoneDetectionChange(boolean enabled) {
                if (enabled) {
                    handleAutoTimeZoneEnabled();
                }
            }
        });
    }

    @Override
    public void handleCountryDetected(String countryIsoCode) {
        String oldCountryIsoCode = mCountryIsoCode;
        mCountryIsoCode = Objects.requireNonNull(countryIsoCode);

        if (!TextUtils.isEmpty(countryIsoCode) && !mNitzTimeZoneDetectionSuccessful) {
            updateTimeZoneFromNetworkCountryCode(countryIsoCode);
        }

        boolean countryChanged = Objects.equals(oldCountryIsoCode, countryIsoCode);
        if (mLatestNitzSignal != null && (countryChanged || oldCountryIsoCode == null)) {
            updateTimeZoneFromCountryAndNitz();
        }
    }

    private void updateTimeZoneFromCountryAndNitz() {
        // This method must only be called after mLatestNitzSignal has been set to a non-null
        // value.
        TimestampedValue<NitzData> nitzSignal = Objects.requireNonNull(mLatestNitzSignal);
        String isoCountryCode = mCountryIsoCode;

        // TimeZone.getDefault() returns a default zone (GMT) even when time zone have never
        // been set which makes it difficult to tell if it's what the user / time zone detection
        // has chosen. isTimeZoneSettingInitialized() tells us whether the time zone of the
        // device has ever been explicitly set by the user or code.
        final boolean isTimeZoneSettingInitialized =
                mTimeServiceHelper.isTimeZoneSettingInitialized();

        if (DBG) {
            Rlog.d(LOG_TAG, "updateTimeZoneFromCountryAndNitz:"
                    + " isTimeZoneSettingInitialized=" + isTimeZoneSettingInitialized
                    + " nitzSignal=" + nitzSignal
                    + " isoCountryCode=" + isoCountryCode);
        }

        try {
            NitzData nitzData = nitzSignal.getValue();

            String zoneId;
            if (nitzData.getEmulatorHostTimeZone() != null) {
                zoneId = nitzData.getEmulatorHostTimeZone().getID();
            } else if (isoCountryCode == null) {
                // We don't have a country code so we won't try to look up the time zone.
                zoneId = null;
            } else if (TextUtils.isEmpty(isoCountryCode)) {
                // We have a country code but it's empty. This is most likely because we're on a
                // test network that's using a bogus MCC (eg, "001"). Obtain a TimeZone based only
                // on the NITZ parameters: it's only going to be correct in a few cases but it
                // should at least have the correct offset.
                OffsetResult lookupResult = mTimeZoneLookupHelper.lookupByNitz(nitzData);
                String logMsg = "updateTimeZoneFromCountryAndNitz: lookupByNitz returned"
                        + " lookupResult=" + lookupResult;
                if (DBG) {
                    Rlog.d(LOG_TAG, logMsg);
                }
                // We log this in the time zone log because it has been a source of bugs.
                mTimeZoneLog.log(logMsg);

                zoneId = lookupResult != null ? lookupResult.getTimeZone().getID() : null;
            } else if (isTimeZoneSettingInitialized
                    && isNitzSignalOffsetInfoBogus(nitzSignal, isoCountryCode)) {
                String logMsg = "updateTimeZoneFromCountryAndNitz: Received NITZ looks bogus, "
                        + " isoCountryCode=" + isoCountryCode
                        + " nitzSignal=" + nitzSignal;
                if (DBG) {
                    Rlog.d(LOG_TAG, logMsg);
                }
                // We log this in the time zone log because it has been a source of bugs.
                mTimeZoneLog.log(logMsg);

                zoneId = null;
            } else {
                OffsetResult lookupResult =
                        mTimeZoneLookupHelper.lookupByNitzCountry(nitzData, isoCountryCode);
                if (lookupResult != null) {
                    if (DBG) {
                        Rlog.d(LOG_TAG, "updateTimeZoneFromCountryAndNitz: using"
                                + " lookupByNitzCountry(nitzData, isoCountryCode),"
                                + " nitzData=" + nitzData
                                + " isoCountryCode=" + isoCountryCode
                                + " lookupResult=" + lookupResult);
                    }
                    zoneId = lookupResult.getTimeZone().getID();
                } else {
                    // The country + offset provided no match, so see if the country by itself
                    // would be enough.
                    CountryResult countryResult = mTimeZoneLookupHelper.lookupByCountry(
                            isoCountryCode, nitzData.getCurrentTimeInMillis());
                    if (DBG) {
                        Rlog.d(LOG_TAG, "updateTimeZoneFromCountryAndNitz: fallback to"
                                + " lookupByCountry(isoCountryCode, whenMillis),"
                                + " nitzData=" + nitzData
                                + " isoCountryCode=" + isoCountryCode
                                + " countryResult=" + countryResult);
                    }
                    if (countryResult != null) {
                        // If the country has a single zone, or it has multiple zones but the
                        // default is "boosted" (i.e. it is considered a good result in most cases)
                        // then use it.
                        if (countryResult.quality == CountryResult.QUALITY_SINGLE_ZONE
                                || countryResult.quality == CountryResult.QUALITY_DEFAULT_BOOSTED) {
                            zoneId = countryResult.zoneId;
                        } else {
                            // Quality is not high enough.
                            zoneId = null;
                        }
                    } else {
                        // Country not recognized.
                        zoneId = null;
                    }
                }
            }

            String logMsg = "updateTimeZoneFromCountryAndNitz:"
                    + " isTimeZoneSettingInitialized=" + isTimeZoneSettingInitialized
                    + " isoCountryCode=" + isoCountryCode
                    + " nitzSignal=" + nitzSignal
                    + " zoneId=" + zoneId
                    + " isTimeZoneDetectionEnabled()="
                    + mTimeServiceHelper.isTimeZoneDetectionEnabled();

            // Set state as needed.
            if (zoneId != null) {
                if (mTimeServiceHelper.isTimeZoneDetectionEnabled()) {
                    setTimeZone(zoneId, logMsg);
                } else {
                    if (DBG) {
                        logMsg += " [Not setting device time zone]";
                        Rlog.d(LOG_TAG, logMsg);
                    }
                }
                mSavedTimeZoneId = zoneId;
                mNitzTimeZoneDetectionSuccessful = true;
            } else {
                if (DBG) {
                    logMsg += " [Not setting device time zone (zoneId == null)]";
                    Rlog.d(LOG_TAG, logMsg);
                }
                mSavedTimeZoneId = null;
                mNitzTimeZoneDetectionSuccessful = false;
            }
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "updateTimeZoneFromCountryAndNitz: Processing NITZ data"
                    + " nitzSignal=" + nitzSignal
                    + " isoCountryCode=" + isoCountryCode
                    + " isTimeZoneSettingInitialized=" + isTimeZoneSettingInitialized
                    + " ex=" + ex);
        }
    }

    /**
     * Returns true if the NITZ signal is definitely bogus, assuming that the country is correct.
     */
    private boolean isNitzSignalOffsetInfoBogus(
            TimestampedValue<NitzData> nitzSignal, String isoCountryCode) {

        if (TextUtils.isEmpty(isoCountryCode)) {
            // We cannot say for sure.
            return false;
        }

        NitzData newNitzData = nitzSignal.getValue();
        boolean zeroOffsetNitz = newNitzData.getLocalOffsetMillis() == 0;
        return zeroOffsetNitz && !countryUsesUtc(isoCountryCode, nitzSignal);
    }

    private boolean countryUsesUtc(
            String isoCountryCode, TimestampedValue<NitzData> nitzSignal) {
        return mTimeZoneLookupHelper.countryUsesUtc(
                isoCountryCode,
                nitzSignal.getValue().getCurrentTimeInMillis());
    }

    @Override
    public void handleNetworkAvailable() {
        if (DBG) {
            Rlog.d(LOG_TAG, "handleNetworkAvailable: mNitzTimeZoneDetectionSuccessful="
                    + mNitzTimeZoneDetectionSuccessful
                    + ", Setting mNitzTimeZoneDetectionSuccessful=false");
        }
        mNitzTimeZoneDetectionSuccessful = false;
    }

    @Override
    public void handleNetworkUnavailable() {
        if (DBG) {
            Rlog.d(LOG_TAG, "handleNetworkUnavailable: Clearing NITZ and detection state");
        }
        // Clear state related to NITZ.
        mSavedNitzTime = null;
        mTimeLog.log("handleNetworkUnavailable: NITZ state cleared.");

        TimestampedValue<NitzData> oldNitzSignal = mLatestNitzSignal;
        mLatestNitzSignal = null;
        mNitzTimeZoneDetectionSuccessful = false;
        mSavedTimeZoneId = null;
        mTimeZoneLog.log("handleNetworkUnavailable: NITZ state cleared.");

        // Avoid doing work unnecessarily: if the mLatestNitzSignal was already null we have nothing
        // to do as it will have been done last time the mLatestNitzSignal was cleared.
        if (oldNitzSignal == null) {
            return;
        }

        // mSavedTimeZoneId has been cleared but using only the country information that is left
        // might be sufficient to detect the time zone.
        String isoCountryCode = mCountryIsoCode;
        // We don't need to do country-based time zone detection if the isoCountryCode is null
        // (unknown) or empty (test cell). TextUtils.isEmpty() does both checks in one.
        if (!TextUtils.isEmpty(isoCountryCode)) {
            updateTimeZoneFromNetworkCountryCode(isoCountryCode);
        }
        sendEmptyTimeSuggestion("handleNetworkUnavailable");
    }

    @Override
    public void handleCountryUnavailable() {
        if (DBG) {
            Rlog.d(LOG_TAG, "handleNetworkCountryCodeUnavailable");
        }

        mSavedTimeZoneId = null;
        mCountryIsoCode = null;
        mNitzTimeZoneDetectionSuccessful = false;
    }

    @Override
    public void handleNitzReceived(TimestampedValue<NitzData> nitzSignal) {
        // Always store the latest NITZ signal received.
        mLatestNitzSignal = Objects.requireNonNull(nitzSignal);

        updateTimeZoneFromCountryAndNitz();
        updateTimeFromNitz();
    }

    @Override
    public void handleAirplaneModeChanged(boolean on) {
        if (DBG) {
            Rlog.d(LOG_TAG, "handleAirplaneModeChanged: on=" + on);
        }

        // Treat entry / exit from airplane mode as a strong signal that the user wants to clear
        // cached state. If the user really is boarding a plane they won't want cached state from
        // before their flight influencing behavior.
        //
        // State is cleared on entry AND exit: on entry because the detection code shouldn't be
        // opinionated while in airplane mode, and on exit to avoid any unexpected signals received
        // while in airplane mode from influencing behavior afterwards.
        //
        // After clearing detection state, the time zone detection should work out from first
        // principles what the time / time zone is. This assumes calls like handleNetworkAvailable()
        // will be made after airplane mode is re-enabled as the device re-establishes network
        // connectivity.
        mSavedNitzTime = null;
        mTimeLog.log("handleAirplaneModeChanged(" + on + "): Time state cleared.");

        mCountryIsoCode = null;

        TimestampedValue<NitzData> oldNitzSignal = mLatestNitzSignal;
        mLatestNitzSignal = null;
        mNitzTimeZoneDetectionSuccessful = false;
        mSavedTimeZoneId = null;

        if (oldNitzSignal == null) {
            // No work to do - terminate early.
            return;
        }

        mTimeZoneLog.log("handleAirplaneModeChanged(" + on + "): Time zone state cleared.");
        sendEmptyTimeSuggestion("handleAirplaneModeChanged(" + on + ")");
    }

    private void sendEmptyTimeSuggestion(String reason) {
        TelephonyTimeSuggestion timeSuggestion =
                new TelephonyTimeSuggestion.Builder(mPhone.getPhoneId()).build();
        timeSuggestion.addDebugInfo("Empty suggestion, reason=" + reason);
        mTimeServiceHelper.suggestDeviceTime(timeSuggestion);
    }

    private void updateTimeFromNitz() {
        TimestampedValue<NitzData> nitzSignal = Objects.requireNonNull(mLatestNitzSignal);
        try {
            boolean ignoreNitz = mDeviceState.getIgnoreNitz();
            if (ignoreNitz) {
                if (DBG) {
                    Rlog.d(LOG_TAG, "updateTimeFromNitz: Not suggesting system clock because"
                            + " gsm.ignore-nitz is set");
                }
                return;
            }

            // Validate the nitzTimeSignal to reject obviously bogus elapsedRealtime values.
            try {
                // Acquire the wake lock as we are reading the elapsed realtime clock below.
                mWakeLock.acquire();

                long elapsedRealtime = mDeviceState.elapsedRealtime();
                long millisSinceNitzReceived =
                        elapsedRealtime - nitzSignal.getReferenceTimeMillis();
                if (millisSinceNitzReceived < 0 || millisSinceNitzReceived > Integer.MAX_VALUE) {
                    if (DBG) {
                        Rlog.d(LOG_TAG, "updateTimeFromNitz: not setting time, unexpected"
                                + " elapsedRealtime=" + elapsedRealtime
                                + " nitzSignal=" + nitzSignal);
                    }
                    return;
                }
            } finally {
                mWakeLock.release();
            }

            TimestampedValue<Long> newNitzTime = new TimestampedValue<>(
                    nitzSignal.getReferenceTimeMillis(),
                    nitzSignal.getValue().getCurrentTimeInMillis());

            // Perform rate limiting: a NITZ signal received too close to a previous
            // one will be disregarded unless there is a significant difference between the
            // UTC times they represent.
            if (mSavedNitzTime != null) {
                int nitzUpdateSpacing = mDeviceState.getNitzUpdateSpacingMillis();
                int nitzUpdateDiff = mDeviceState.getNitzUpdateDiffMillis();

                // Calculate the elapsed time between the new signal and the last signal.
                long elapsedRealtimeSinceLastSaved = newNitzTime.getReferenceTimeMillis()
                        - mSavedNitzTime.getReferenceTimeMillis();

                // Calculate the UTC difference between the time the two signals hold.
                long utcTimeDifferenceMillis =
                        newNitzTime.getValue() - mSavedNitzTime.getValue();

                // Ideally the difference between elapsedRealtimeSinceLastSaved and
                // utcTimeDifferenceMillis would be zero.
                long millisGained = utcTimeDifferenceMillis - elapsedRealtimeSinceLastSaved;

                if (elapsedRealtimeSinceLastSaved <= nitzUpdateSpacing
                        && Math.abs(millisGained) <= nitzUpdateDiff) {
                    if (DBG) {
                        Rlog.d(LOG_TAG, "updateTimeFromNitz: not setting time. NITZ signal is"
                                + " too similar to previous value received "
                                + " mSavedNitzTime=" + mSavedNitzTime
                                + ", nitzSignal=" + nitzSignal
                                + ", nitzUpdateSpacing=" + nitzUpdateSpacing
                                + ", nitzUpdateDiff=" + nitzUpdateDiff);
                    }
                    return;
                }
            }

            String logMsg = "updateTimeFromNitz: suggesting system clock update"
                    + " nitzSignal=" + nitzSignal
                    + ", newNitzTime=" + newNitzTime
                    + ", mSavedNitzTime= " + mSavedNitzTime;
            if (DBG) {
                Rlog.d(LOG_TAG, logMsg);
            }
            mTimeLog.log(logMsg);
            TelephonyTimeSuggestion timeSuggestion =
                    new TelephonyTimeSuggestion.Builder(mPhone.getPhoneId())
                            .setUtcTime(newNitzTime)
                            .addDebugInfo(logMsg)
                            .build();
            mTimeServiceHelper.suggestDeviceTime(timeSuggestion);

            TelephonyMetrics.getInstance().writeNITZEvent(
                    mPhone.getPhoneId(), newNitzTime.getValue());

            // Save the last NITZ time signal that was suggested to enable rate limiting.
            mSavedNitzTime = newNitzTime;
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "updateTimeFromNitz: Processing NITZ data"
                    + " nitzSignal=" + nitzSignal
                    + " ex=" + ex);
        }
    }

    private void setTimeZone(String zoneId, String logMessage) {
        logMessage += " [Setting device time zone to zoneId=" + zoneId + "]";
        if (DBG) {
            Rlog.d(LOG_TAG, logMessage);
        }

        // Filter mTimeZoneLog entries to only store "interesting" ones. NITZ signals can be
        // quite frequent (e.g. every few minutes) and logging each one soon obliterates useful
        // entries from bug reports. http://b/138187241
        if (!zoneId.equals(mLastSetTimeZoneId)) {
            mTimeZoneLog.log(logMessage);
            mLastSetTimeZoneId = zoneId;
        }

        mTimeServiceHelper.setDeviceTimeZone(zoneId);

        if (DBG) {
            Rlog.d(LOG_TAG,
                    "setAndBroadcastNetworkSetTimeZone: called setDeviceTimeZone()"
                            + " zoneId=" + zoneId);
        }
    }

    private void handleAutoTimeZoneEnabled() {
        String logMsg = "handleAutoTimeZoneEnabled: "
                + " mSavedTimeZoneId=" + mSavedTimeZoneId;
        if (mSavedTimeZoneId != null) {
            setTimeZone(mSavedTimeZoneId, logMsg);
        } else {
            if (DBG) {
                logMsg += " [Not setting device time zone]";
                Rlog.d(LOG_TAG, logMsg);
            }
        }
    }

    @Override
    public void dumpState(PrintWriter pw) {
        // Time Detection State
        pw.println(" mSavedTime=" + mSavedNitzTime);

        // Time Zone Detection State
        pw.println(" mLatestNitzSignal=" + mLatestNitzSignal);
        pw.println(" mCountryIsoCode=" + mCountryIsoCode);
        pw.println(" mSavedTimeZoneId=" + mSavedTimeZoneId);
        pw.println(" mNitzTimeZoneDetectionSuccessful=" + mNitzTimeZoneDetectionSuccessful);

        // Miscellaneous
        pw.println(" mWakeLock=" + mWakeLock);
        pw.flush();
    }

    @Override
    public void dumpLogs(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        ipw.println(" Time Logs:");
        ipw.increaseIndent();
        mTimeLog.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println(" Time zone Logs:");
        ipw.increaseIndent();
        mTimeZoneLog.dump(fd, ipw, args);
        ipw.decreaseIndent();
    }

    /**
     * Update time zone by network country code, works well on countries which only have one time
     * zone or multiple zones with the same offset.
     *
     * @param iso Country code from network MCC
     */
    private void updateTimeZoneFromNetworkCountryCode(String iso) {
        CountryResult lookupResult = mTimeZoneLookupHelper.lookupByCountry(
                iso, mDeviceState.currentTimeMillis());
        boolean isTimeZoneSettingInitialized = mTimeServiceHelper.isTimeZoneSettingInitialized();
        if (lookupResult != null
                && (!isTimeZoneSettingInitialized
                        || lookupResult.quality == CountryResult.QUALITY_SINGLE_ZONE
                        || lookupResult.quality == CountryResult.QUALITY_DEFAULT_BOOSTED)) {
            String logMsg = "updateTimeZoneFromNetworkCountryCode: tz result found"
                    + " iso=" + iso
                    + " lookupResult=" + lookupResult
                    + " isTimeZoneSettingInitialized=" + isTimeZoneSettingInitialized;

            String zoneId = lookupResult.zoneId;
            if (mTimeServiceHelper.isTimeZoneDetectionEnabled()) {
                setTimeZone(zoneId, logMsg);
            } else {
                if (DBG) {
                    logMsg += " [Not setting device time zone]";
                    Rlog.d(LOG_TAG, logMsg);
                }
            }
            mSavedTimeZoneId = zoneId;
        } else {
            if (DBG) {
                Rlog.d(LOG_TAG, "updateTimeZoneFromNetworkCountryCode: no good zone for"
                        + " iso=" + iso
                        + " lookupResult=" + lookupResult);
            }
            mSavedTimeZoneId = null;
        }
    }

    // VisibleForTesting
    public boolean getNitzTimeZoneDetectionSuccessful() {
        return mNitzTimeZoneDetectionSuccessful;
    }

    // VisibleForTesting
    public NitzData getCachedNitzData() {
        return mLatestNitzSignal != null ? mLatestNitzSignal.getValue() : null;
    }

    // VisibleForTesting
    public String getSavedTimeZoneId() {
        return mSavedTimeZoneId;
    }
}
