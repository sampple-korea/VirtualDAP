package com.virtualdap.host;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import top.niunaijun.blackbox.utils.IntentSanitizer;
import static org.junit.Assert.*;

/** A control process without app classes must not consume or discard application payloads. */
@RunWith(AndroidJUnit4.class)
public final class IntentPayloadInstrumentedTest {
    @Test public void unknownNestedParcelableAndClassSurviveOpaqueForwarding() {
        Bundle controller = new Bundle();
        controller.putParcelable("controller", new Controller("original-state"));
        controller.putSerializable("class", Controller.class);
        controller.putString("_B_|_class_extra_|ordinary", "not-a-runtime-marker");
        Intent request = new Intent("fixture.CONTINUE").putExtra("state", controller);
        ArrayList<Bundle> stack = new ArrayList<>();
        stack.add(controller);
        request.putParcelableArrayListExtra("stack", stack);

        Intent transit = parcel(request);
        transit.setExtrasClassLoader(new ClassLoader(null) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(Controller.class.getName())) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        });
        IntentSanitizer.sanitizeClassExtrasForIpc(transit);
        Intent received = parcel(transit);
        IntentSanitizer.restoreSanitizedClassExtras(received, Controller.class.getClassLoader());
        Bundle state = received.getBundleExtra("state");
        assertNotNull(state);
        assertEquals("original-state", state.getParcelable("controller", Controller.class).value);
        assertSame(Controller.class, state.getSerializable("class"));
        assertEquals("not-a-runtime-marker", state.getString("_B_|_class_extra_|ordinary"));
        ArrayList<Bundle> restored = received.getParcelableArrayListExtra("stack", Bundle.class);
        assertEquals("original-state", restored.get(0).getParcelable("controller", Controller.class).value);
    }

    @Test public void selectorPayloadAndNullInputsArePreserved() {
        Intent target = new Intent().putExtra("controller", new Controller("selector"));
        Intent wrapper = new Intent();
        wrapper.setSelector(target);
        IntentSanitizer.sanitizeClassExtrasForIpc(null);
        IntentSanitizer.restoreSanitizedClassExtras(null, null);
        Intent received = parcel(wrapper);
        IntentSanitizer.restoreSanitizedClassExtras(received, Controller.class.getClassLoader());
        assertEquals("selector", received.getSelector().getParcelableExtra("controller", Controller.class).value);
    }

    private static Intent parcel(Intent source) {
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Intent.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

    public static final class Controller implements Parcelable {
        final String value;
        Controller(String value) { this.value = value; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel target, int flags) { target.writeString(value); }
        public static final Creator<Controller> CREATOR = new Creator<Controller>() {
            @Override public Controller createFromParcel(Parcel source) { return new Controller(source.readString()); }
            @Override public Controller[] newArray(int size) { return new Controller[size]; }
        };
    }
}
