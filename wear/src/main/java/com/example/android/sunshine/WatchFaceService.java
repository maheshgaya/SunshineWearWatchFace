/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
// * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final String TAG = WatchFaceService.class.getSimpleName();
    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }


    private static class EngineHandler extends Handler {
        private final WeakReference<WatchFaceService.Engine> mWeakReference;

        public EngineHandler(WatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener{
        private final String TAG = Engine.class.getSimpleName();
        private final int TIMEOUT_MS = 1000;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDayTextPaint;
        Paint mSeparatorPaint;
        Paint mMinTempPaint;
        Paint mMaxTempPaint;

        boolean mAmbient;
        Calendar mCalendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mXPadding;
        float mYPadding;
        float mYDividerPadding;
        float mAmPmYOffset;
        float mXTempOffset;
        float mXBitmapOffset;

        GoogleApiClient mGoogleApiClient;

        //Weather conditions
        Bitmap imageBitmap;
        String minTemp = "";
        String maxTemp = "";

        /*
         * Data Items for Wearables
         */
        //Paths
        public static final String WEATHER_WEAR_PATH = "/wearweather";
        //keys
        public static final String WEATHER_IMAGE_KEY = "image";
        public static final String WEATHER_MAX_KEY = "max";
        public static final String WEATHER_MIN_KEY = "min";
        public static final String TIME_KEY = "time";

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = WatchFaceService.this.getResources();
            mYPadding = resources.getDimension(R.dimen.digital_y_padding);
            mYDividerPadding = resources.getDimension(R.dimen.digital_y_divide_padding);
            mAmPmYOffset = resources.getDimension(R.dimen.digital_ampm_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDayTextPaint = new Paint();
            mDayTextPaint = createTextPaint(resources.getColor(R.color.digital_off_text));

            mSeparatorPaint = new Paint();
            mSeparatorPaint = createTextPaint(resources.getColor(R.color.digital_off_text));
            mSeparatorPaint.setStrokeWidth(1);

            mMinTempPaint = new Paint();
            mMinTempPaint = createTextPaint(resources.getColor(R.color.digital_off_text));

            mMaxTempPaint = new Paint();
            mMaxTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(WatchFaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            //remove GoogleApiClient connections
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()){
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);

        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mXPadding = resources.getDimension(isRound ? R.dimen.digital_x_padding_round : R.dimen.digital_x_padding);
            mYOffset = resources.getDimension(isRound ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);

            mXBitmapOffset = resources.getDimension(isRound ? R.dimen.digital_x_bitmap_offset_round : R.dimen.digital_x_bitmap_offset);
            mXTempOffset = resources.getDimension(isRound ? R.dimen.digital_temp_offset_round : R.dimen.digital_temp_offset);
            mTextPaint.setTextSize(textSize);
            mMinTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_text_size));
            mMaxTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_text_size));
            mDayTextPaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    boolean antiAliasing = !inAmbientMode;
                    mTextPaint.setAntiAlias(antiAliasing);
                    mDayTextPaint.setAntiAlias(antiAliasing);
                    mMaxTempPaint.setAntiAlias(antiAliasing);
                    mMinTempPaint.setAntiAlias(antiAliasing);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            boolean is24Hour = android.text.format.DateFormat.is24HourFormat(WatchFaceService.this);

            //Draw Time
            String timeText;
            float xCoordinates;
            if (is24Hour){
                timeText = String.format(Locale.getDefault(), "%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                        mCalendar.get(Calendar.MINUTE));
                xCoordinates = mXOffset;
            } else {
                String amPm = mCalendar.get(Calendar.HOUR_OF_DAY) >= 12 ? "PM" : "AM";
                float yAmPmCoordinates = amPm.equals("PM") ? mYOffset : mYOffset - mAmPmYOffset;
                float xAmPmCoordinates = mXOffset + (mTextPaint.getTextSize() * 2) + mXPadding;
                int hour = mCalendar.get(Calendar.HOUR);
                hour = (hour == 0) ? 12: hour; //hour is represented from 0 to 11. (12 o'clock is 0)
                timeText = String.format(Locale.getDefault(), "%02d:%02d ", hour,
                        mCalendar.get(Calendar.MINUTE));

                canvas.drawText(amPm, xAmPmCoordinates, yAmPmCoordinates, mDayTextPaint);
                xCoordinates = mXOffset - mXPadding;
            }

            canvas.drawText(timeText, xCoordinates, mYOffset, mTextPaint);

            //Draw date in this form: FRI, JAN 22 2017
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());

            String currentDate = dateFormat.format(mCalendar.getTime()).toUpperCase();
            canvas.drawText(currentDate, mXOffset - mXPadding, mYOffset + mYPadding, mDayTextPaint);

            //Draw a separator
            float yDividerCoordinates = mYOffset + mYPadding + mYDividerPadding;
            canvas.drawLine(canvas.getWidth()/2 - 30f, yDividerCoordinates, canvas.getWidth()/2 + 30f, yDividerCoordinates, mSeparatorPaint);

            float yTempCoordinates = yDividerCoordinates + (mYPadding * 1.6f);
            if (minTemp.length() != 0 && maxTemp.length() != 0) {
                canvas.drawText(maxTemp, mXTempOffset, yTempCoordinates, mMaxTempPaint);
                canvas.drawText(minTemp , mXTempOffset + (mDayTextPaint.getTextSize() * 2.8f), yTempCoordinates, mMinTempPaint);
            }
            if (imageBitmap != null){
                canvas.drawBitmap(imageBitmap, mXBitmapOffset, yDividerCoordinates + 15f, null);
            }

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged: inside method");


            for (DataEvent event: dataEventBuffer){
                if (event.getType() == DataEvent.TYPE_CHANGED &&
                event.getDataItem().getUri().getPath().compareTo(WEATHER_WEAR_PATH) == 0){
                    updateUIFromDataItems(event.getDataItem());
                }
            }

            dataEventBuffer.release();
        }

        public void updateUIFromDataItems(DataItem dataItem){
            DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
            if (imageBitmap != null) {
                imageBitmap.recycle();
                imageBitmap = null;
            }
            new LoadBitmapTask().execute(dataMap.getAsset(WEATHER_IMAGE_KEY));
            maxTemp = dataMap.getString(WEATHER_MAX_KEY);
            minTemp = dataMap.getString(WEATHER_MIN_KEY);
            Log.d(TAG, "onDataChanged: " + minTemp + "-:-" + maxTemp);

        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(@NonNull DataItemBuffer dataItems) {
                    for (DataItem dataItem: dataItems){
                        Log.d(TAG, "onResult: " + dataItem.toString());
                        updateUIFromDataItems(dataItem);
                    }
                    dataItems.release();
                    if (isVisible() && !isInAmbientMode()) {
                        invalidate();
                    }
                }
            });
            Log.d(TAG, "onConnected: mGoogleApiClient connected");
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "onConnectionSuspended: mGoogleApiClient connection suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed: " + connectionResult.getErrorMessage());
        }

        public class LoadBitmapTask extends AsyncTask<Asset, Void, Bitmap>{

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    imageBitmap = Bitmap.createScaledBitmap(bitmap, 50, 50, false) ;
                }
            }

            @Override
            protected Bitmap doInBackground(Asset... params) {
                if (params.length != 0){
                    Asset asset = params[0];
                    if (asset == null){
                        Log.d(TAG, "doInBackground: Asset is null");
                        return null;
                    }

                    InputStream assetInputStream = Wearable.DataApi
                            .getFdForAsset(mGoogleApiClient, asset).await().getInputStream();
                    if (assetInputStream == null){
                        Log.d(TAG, "doInBackground: No image found");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);
                }
                return null;
            }
        }
    }

}
