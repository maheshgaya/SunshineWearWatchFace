/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.example.android.sunshine.sync;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentSender;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.CursorLoader;
import android.text.format.DateUtils;
import android.util.Log;

import com.example.android.sunshine.MainActivity;
import com.example.android.sunshine.data.SunshinePreferences;
import com.example.android.sunshine.data.WeatherContract;
import com.example.android.sunshine.utilities.NetworkUtils;
import com.example.android.sunshine.utilities.NotificationUtils;
import com.example.android.sunshine.utilities.OpenWeatherJsonUtils;
import com.example.android.sunshine.utilities.SunshineDateUtils;
import com.example.android.sunshine.utilities.SunshineWeatherUtils;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.net.URL;
import java.util.Calendar;

public class SunshineSyncTask{

    private static final String TAG = SunshineSyncTask.class.getSimpleName();
    private static boolean mResolvingError = false;
    /**
     * Performs the network request for updated weather, parses the JSON from that request, and
     * inserts the new weather information into our ContentProvider. Will notify the user that new
     * weather has been loaded if the user hasn't been notified of the weather within the last day
     * AND they haven't disabled notifications in the preferences screen.
     *
     * @param context Used to access utility methods and the ContentResolver
     */
    synchronized public static void syncWeather(Context context) {
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(@Nullable Bundle bundle) {
                        Log.d(TAG, "onConnected: GoogleApiClient connected");
                    }

                    @Override
                    public void onConnectionSuspended(int i) {

                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                        Log.d(TAG, "onConnectionFailed: GoogleApiClient failed");
                    }
                })
                .build();
        googleApiClient.connect();

        Uri forecastQueryUri = WeatherContract.WeatherEntry.CONTENT_URI;
                /* Sort order: Ascending by date */
        String sortOrder = WeatherContract.WeatherEntry.COLUMN_DATE + " ASC";
                /*
                 * A SELECTION in SQL declares which rows you'd like to return. In our case, we
                 * want all weather data from today onwards that is stored in our weather table.
                 * We created a handy method to do that in our WeatherEntry class.
                 */
        String selection = WeatherContract.WeatherEntry.getSqlSelectForTodayOnwards();


        try {
            /*
             * The getUrl method will return the URL that we need to get the forecast JSON for the
             * weather. It will decide whether to create a URL based off of the latitude and
             * longitude or off of a simple location as a String.
             */
            URL weatherRequestUrl = NetworkUtils.getUrl(context);

            /* Use the URL to retrieve the JSON */
            String jsonWeatherResponse = NetworkUtils.getResponseFromHttpUrl(weatherRequestUrl);

            /* Parse the JSON into a list of weather values */
            ContentValues[] weatherValues = OpenWeatherJsonUtils
                    .getWeatherContentValuesFromJson(context, jsonWeatherResponse);

            /*
             * In cases where our JSON contained an error code, getWeatherContentValuesFromJson
             * would have returned null. We need to check for those cases here to prevent any
             * NullPointerExceptions being thrown. We also have no reason to insert fresh data if
             * there isn't any to insert.
             */
            if (weatherValues != null && weatherValues.length != 0) {
                /* Get a handle on the ContentResolver to delete and insert data */
                ContentResolver sunshineContentResolver = context.getContentResolver();

                /* Delete old weather data because we don't need to keep multiple days' data */
                sunshineContentResolver.delete(
                        WeatherContract.WeatherEntry.CONTENT_URI,
                        null,
                        null);

                /* Insert our new weather data into Sunshine's ContentProvider */
                sunshineContentResolver.bulkInsert(
                        WeatherContract.WeatherEntry.CONTENT_URI,
                        weatherValues);

                /*
                 * Finally, after we insert data into the ContentProvider, determine whether or not
                 * we should notify the user that the weather has been refreshed.
                 */
                boolean notificationsEnabled = SunshinePreferences.areNotificationsEnabled(context);

                /*
                 * If the last notification was shown was more than 1 day ago, we want to send
                 * another notification to the user that the weather has been updated. Remember,
                 * it's important that you shouldn't spam your users with notifications.
                 */
                long timeSinceLastNotification = SunshinePreferences
                        .getEllapsedTimeSinceLastNotification(context);

                boolean oneDayPassedSinceLastNotification = false;

                if (timeSinceLastNotification >= DateUtils.DAY_IN_MILLIS) {
                    oneDayPassedSinceLastNotification = true;
                }

                /*
                 * We only want to show the notification if the user wants them shown and we
                 * haven't shown a notification in the past day.
                 */
                if (notificationsEnabled && oneDayPassedSinceLastNotification) {
                    NotificationUtils.notifyUserOfNewWeather(context);
                }

            /* If the code reaches this point, we have successfully performed our sync */

            }

        } catch (Exception e) {
            /* Server probably invalid */
            e.printStackTrace();
        }

        /* Query the database and update the dataItems*/
        Cursor cursor = context.getContentResolver().query(forecastQueryUri,
                MainActivity.MAIN_FORECAST_PROJECTION,
                selection,
                null,
                sortOrder);
        try {
            if (cursor.moveToFirst()) {
                String friendlyDate = SunshineDateUtils.getFriendlyDateString(context, cursor.getLong(MainActivity.INDEX_WEATHER_DATE), false);
                String max = SunshineWeatherUtils.formatTemperature(context, cursor.getDouble(MainActivity.INDEX_WEATHER_MAX_TEMP));
                String min = SunshineWeatherUtils.formatTemperature(context, cursor.getDouble(MainActivity.INDEX_WEATHER_MIN_TEMP));

                int weatherIconId = cursor.getInt(MainActivity.INDEX_WEATHER_CONDITION_ID);
                Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), SunshineWeatherUtils.getSmallArtResourceIdForWeatherCondition(weatherIconId));
                Asset imageAsset = MainActivity.createAssetFromBitmap(bitmap);
                PutDataRequest request = sendWeatherWearData(imageAsset, min, max);

                PendingResult<DataApi.DataItemResult> pendingResult =
                        Wearable.DataApi.putDataItem(googleApiClient, request);

                pendingResult
                        .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                            @Override
                            public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                                Log.d(TAG, "onResult: Image was successfully sent to wear: " + dataItemResult.getStatus());
                            }
                        });
            }
        } finally {
            cursor.close();
        }
        if (googleApiClient.isConnected() && googleApiClient != null) {
            googleApiClient.disconnect();
        }

    }

    private static PutDataRequest sendWeatherWearData( Asset asset, String minTemp, String maxTemp){
        Log.d(TAG, "sendWeatherWearData: " + minTemp + " : " + maxTemp);
        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(MainActivity.WEATHER_WEAR_PATH);
        putDataMapRequest.getDataMap().putAsset(MainActivity.WEATHER_IMAGE_KEY, asset);
        putDataMapRequest.getDataMap().putString(MainActivity.WEATHER_MAX_KEY, maxTemp);
        putDataMapRequest.getDataMap().putString(MainActivity.WEATHER_MIN_KEY, minTemp);
        putDataMapRequest.getDataMap().putString(MainActivity.TIME_KEY, Calendar.getInstance().getTime().toString());
        PutDataRequest request = putDataMapRequest.asPutDataRequest();
        request.setUrgent();
        return request;

    }

}