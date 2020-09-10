package com.kazuki.depthreconstruction.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.kazuki.depthreconstruction.R;

public class HomeActivity extends AppCompatActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_home);

    Button button01 = findViewById(R.id.button_InpaintDepth);
    button01.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        Intent intent = new Intent(getApplication(), InpaintDepthActivity.class);
        startActivity(intent);
      }
    });

    Button button02 = findViewById(R.id.button_CastShadow);
    button02.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        Intent intent = new Intent(getApplication(), CastShadowActivity.class);
        startActivity(intent);
      }
    });

  }
}
