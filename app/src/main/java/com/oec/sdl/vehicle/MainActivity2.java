package com.oec.sdl.vehicle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

public class MainActivity2 extends AppCompatActivity {
    private TextView SC, EW, SP;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);
        SC = findViewById(R.id.SC);
        EW = findViewById(R.id.EW);
        SP = findViewById(R.id.SP);

        Button button = findViewById(R.id.button4);
        button.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                Intent raffleIntent = new Intent(MainActivity2.this, RaffleActivity.class);
                startActivity(raffleIntent);
            }
        });

        // receiver
        UpdateReceiver receiver = new UpdateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("UPDATE_VIEW");
        registerReceiver(receiver, filter);
    }

    protected class UpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            String msg[] = extras.getString("message").split(",");

            SP.setText(msg[0]);
            EW.setText(msg[1]);
            SC.setText(msg[2]);

            ImageView carImage = findViewById(R.id.imageView4);
//            TranslateAnimation anim = new TranslateAnimation(0,Integer.parseInt(msg[1]) * 10,0,0);
//            anim.setDuration(700);
//            carImage.startAnimation(anim);
            carImage.setTranslationX(Integer.parseInt(msg[1])*10);
        }
    }
}