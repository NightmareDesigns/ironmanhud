package com.nightmaredesigns.dexhud;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;

/** One owner at a time: activity normally, foreground capture service during handoff. */
final class HudDisplay implements DisplayManager.DisplayListener {
    private final Context context;
    private final HudState state;
    private final DisplayManager manager;
    private final Runnable detached;
    private Presentation presentation;
    private HudView view;
    private boolean attached;

    HudDisplay(Context context, HudState state, Runnable detached) {
        this.context = context;
        this.state = state;
        this.detached = detached;
        manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    }

    void start() {
        if (!attached) {
            attached = true;
            manager.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
        }
        refresh();
    }

    void refresh() {
        if (!attached) return;
        if (presentation != null && presentation.getDisplay().getDisplayId() != state.displayId) close();
        if (state.displayId < 0) { close(); return; }
        Display selected = null;
        for (Display display : manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (display.getDisplayId() == state.displayId && display.isValid()) selected = display;
        }
        if (selected == null) { lostDisplay(); return; }
        if (presentation == null) {
            try {
                Presentation next = new Presentation(context, selected,
                        android.R.style.Theme_Material_NoActionBar_Fullscreen);
                HudView nextView = new HudView(next.getContext());
                state.apply(nextView);
                next.setContentView(nextView);
                next.setOnDismissListener(dialog -> {
                    nextView.setRunning(false);
                    if (presentation == next) {
                        presentation = null;
                        view = null;
                    }
                });
                next.setOnCancelListener(dialog -> {
                    if (attached && state.displayId == next.getDisplay().getDisplayId()) lostDisplay();
                });
                presentation = next;
                view = nextView;
                next.show();
                next.getWindow().setBackgroundDrawableResource(android.R.color.black);
                next.getWindow().setNavigationBarColor(Color.BLACK);
                next.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                nextView.requestFocus();
                view.setRunning(true);
            } catch (RuntimeException exception) {
                // A display/token can become invalid between enumeration and window creation.
                lostDisplay();
            }
        }
        if (view != null) state.apply(view);
    }

    void stop() {
        if (attached) manager.unregisterDisplayListener(this);
        attached = false;
        close();
    }

    private void close() {
        Presentation old = presentation;
        presentation = null;
        view = null;
        if (old != null) {
            try { old.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    private void lostDisplay() {
        close();
        state.displayId = -1;
        detached.run();
        state.changed();
    }

    @Override public void onDisplayAdded(int id) { refresh(); }
    @Override public void onDisplayRemoved(int id) {
        if (id == state.displayId) lostDisplay();
    }
    @Override public void onDisplayChanged(int id) {
        if (id == state.displayId) {
            close();
            refresh();
        }
    }
}
