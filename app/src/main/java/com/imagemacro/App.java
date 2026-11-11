package com.imagemacro;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        MacroController.get().init(this);
    }
}
