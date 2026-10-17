package com.mozhi.assistant;
/** A geo handler for isolated intent tests. This activity exists only in the test APK. */
public final class MapReceiverActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state){super.onCreate(state);android.widget.TextView view=new android.widget.TextView(this);view.setText(getIntent().getDataString());setContentView(view);}
}
