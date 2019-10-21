package com.oec.sdl.vehicle;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {


    private EditText editText;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        editText = findViewById(R.id.edit_text);
        Button button = findViewById(R.id.button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // エディットテキストのテキストを取得
                String port = editText.getText().toString();

                //If we are connected to a module we want to start our SdlService
                if(BuildConfig.TRANSPORT.equals("MULTI") || BuildConfig.TRANSPORT.equals("MULTI_HB")) {
                    //SdlReceiver.queryForConnectedService(this);
                    Intent sdlServiceIntent = new Intent(getApplication(), SdlService.class); // used for TCP
                    Log.w("sdlServiceIntent",port);
                    sdlServiceIntent.putExtra("port", Integer.parseInt(port) );
                    startService(sdlServiceIntent);
                }else if(BuildConfig.TRANSPORT.equals("TCP")) {
                    Intent proxyIntent = new Intent(getApplication(), SdlService.class);
                    Log.w("intent",port);
                    proxyIntent.putExtra("port", Integer.parseInt(port) );
                    startService(proxyIntent);
                }
            }
        });

    }
    @Override
    protected void onResume() {
        super.onResume();

    }
    @Override
    protected void onPause() {
        super.onPause();
    }



}
