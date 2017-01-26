package com.maheshgaya.android.wear;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Created by Mahesh Gaya on 1/25/17.
 */

public class DataListenerService extends WearableListenerService {

    public static final String WEATHER_WEAR_PATH = "/wearweather";

    public static final String WEATHER_IMAGE_KEY = "image";
    public static final String WEATHER_MAX_KEY = "max";
    public static final String WEATHER_MIN_KEY = "min";
    public static final String TIME_KEY = "time";

    GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
    }


    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        //TODO
    }
}
