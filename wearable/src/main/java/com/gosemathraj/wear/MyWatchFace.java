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

package com.gosemathraj.wear;

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
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DateFormatSymbols;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {

    private static final String LOG_TAG = MyWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private String temp_update = "/sunshine-temp-update";
    private String low_temp = "high-temp";
    private String high_temp = "low-temp";
    private String iconString  = "icon";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
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
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Bitmap icon;

        Paint backgroundPaint;
        Paint timePaint;
        Paint datePaint;
        Paint iconPaint;
        Paint highTempPaint;
        Paint lowTempPaint;
        boolean ambient;
        boolean lowBitAmbient;

        Time time;

        float yOffset;

        String[] days;
        String[] months;

        String lowTemp;
        String highTemp;


        GoogleApiClient mGoogleApiClient;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                time.clear(intent.getStringExtra("time-zone"));
                time.setToNow();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();

            yOffset = resources.getDimension(R.dimen.digital_y_offset);

            backgroundPaint = new Paint();
            backgroundPaint.setColor(resources.getColor(R.color.background));

            timePaint = new Paint();
            timePaint.setColor(resources.getColor(R.color.digital_text_white));
            timePaint.setTypeface(NORMAL_TYPEFACE);
            timePaint.setAntiAlias(true);

            datePaint = new Paint();
            datePaint.setColor(resources.getColor(R.color.digital_text_light_blue));
            datePaint.setTypeface(NORMAL_TYPEFACE);
            datePaint.setAntiAlias(true);

            iconPaint = new Paint();

            highTempPaint = new Paint();
            highTempPaint.setColor(resources.getColor(R.color.digital_text_white));
            highTempPaint.setTypeface(NORMAL_TYPEFACE);
            highTempPaint.setAntiAlias(true);

            lowTempPaint = new Paint();
            lowTempPaint.setColor(resources.getColor(R.color.digital_text_light_blue));
            lowTempPaint.setTypeface(NORMAL_TYPEFACE);
            lowTempPaint.setAntiAlias(true);

            time = new Time();

            DateFormatSymbols symbols = new DateFormatSymbols();
            days = symbols.getShortWeekdays();
            months = symbols.getShortMonths();

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                time.clear(TimeZone.getDefault().getID());
                time.setToNow();
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
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);

            timePaint.setTextSize(timeTextSize);
            datePaint.setTextSize(dateTextSize);
            highTempPaint.setTextSize(tempTextSize);
            lowTempPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            lowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (ambient != inAmbientMode) {
                ambient = inAmbientMode;
                if (lowBitAmbient) {
                    timePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);
            }
            float centerX = bounds.centerX();
            float centerY = bounds.centerY();

            time.setToNow();

            String timeText = String.format("%02d:%02d", time.hour, time.minute);
            float timeTextSize = timePaint.measureText(timeText);
            canvas.drawText(timeText, centerX - timeTextSize/2, yOffset, timePaint);

            String dateText = String.format(
                    "%s, %s %d %d",
                    days[time.weekDay],
                    months[time.month],
                    time.monthDay,
                    time.year
            );
            float dateTextSize = datePaint.measureText(dateText);
            float dateYOffset = yOffset + getResources().getDimension(R.dimen.digital_time_text_margin_bottom);
            canvas.drawText(dateText.toUpperCase(), centerX - dateTextSize/2, dateYOffset, datePaint);

            if (highTemp != null && lowTemp != null) {
                float tempYOffset = dateYOffset + getResources().getDimension(R.dimen.digital_date_text_margin_bottom);

                if(icon != null && !lowBitAmbient)
                    canvas.drawBitmap(icon, centerX - icon.getWidth() - icon.getWidth()/4, tempYOffset - icon.getHeight() / 2, iconPaint);

                canvas.drawText(highTemp, centerX, tempYOffset, highTempPaint);

                float highTempSize = highTempPaint.measureText(highTemp);
                float highTempRightMargin = getResources().getDimension(R.dimen.digital_temp_text_margin_right);

                canvas.drawText(lowTemp, centerX + highTempSize + highTempRightMargin, tempYOffset, lowTempPaint);
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


        /**
         * GoogleApiClient implementation
         */
        @Override
        public void onConnected(Bundle bundle) {
            Log.d(LOG_TAG, "Connected to Google Play...");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "Disconnected from Google Play...");
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "Google Play connection failed...");
        }


        /**
         * DataApi listener
         */
        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(LOG_TAG, "New data received");

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(temp_update) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        highTemp = dataMap.getString(low_temp);
                        lowTemp = dataMap.getString(high_temp);
                        new GetBitmapForWeatherTask().execute(dataMap.getAsset(iconString));

                        invalidate();
                    }
                }
            }

        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null)
                return null;

            ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
            if (!result.isSuccess())
                return null;

            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null)
                return null;

            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        public class GetBitmapForWeatherTask extends AsyncTask<Asset, Void, Void> {

            @Override
            protected Void doInBackground(Asset... assets) {
                Asset asset = assets[0];
                icon = loadBitmapFromAsset(asset);

                int size = Double.valueOf(MyWatchFace.this.getResources().getDimension(R.dimen.digital_icon_size)).intValue();
                icon = Bitmap.createScaledBitmap(icon, size, size, false);
                postInvalidate();

                return null;
            }
        }

    }
}
