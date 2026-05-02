package io.dcloud.feature.uniapp.common;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import java.util.Map;

/**
 * UniComponent stub for CI compilation.
 * Real implementation is in uniapp-v8-release.aar (compileOnly).
 */
public abstract class UniComponent extends View {

    public UniComponent(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setContainerView(View view) {}
    public String getAttr(String name) { return null; }
    public void onCreated() {}
    public void onDestroy() {}
}
