package com.example.scaleImageview;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;

public class MainActivity extends AppCompatActivity {

    private ScaleImageView mScaleImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mScaleImage = findViewById(R.id.iv_scale);
        mScaleImage.setScaleEnable(true);
        mScaleImage.setDoubleTapEnable(true);
    }
}