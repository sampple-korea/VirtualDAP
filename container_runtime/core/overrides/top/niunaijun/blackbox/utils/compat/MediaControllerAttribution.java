package top.niunaijun.blackbox.utils.compat;

import android.media.session.MediaSession;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

/** Attribute OS media-controller calls to this process's actual host UID/package. */
public final class MediaControllerAttribution {
    private static final Set<String> CALLER_METHODS = new HashSet<>(Arrays.asList(
            "sendCommand", "sendMediaButton", "registerCallback", "adjustVolume", "setVolumeTo",
            "prepare", "prepareFromMediaId", "prepareFromSearch", "prepareFromUri", "play",
            "playFromMediaId", "playFromSearch", "playFromUri", "skipToQueueItem", "pause", "stop",
            "next", "previous", "fastForward", "rewind", "seekTo", "rate", "setPlaybackSpeed",
            "sendCustomAction"));
    private static boolean installed;
    private MediaControllerAttribution() {}

    public static synchronized void install() {
        if (installed) return;
        try {
            Field binder = MediaSession.Token.class.getDeclaredField("mBinder");
            binder.setAccessible(true);
            Field creator = MediaSession.Token.class.getDeclaredField("CREATOR");
            creator.setAccessible(true);
            Parcelable.Creator<MediaSession.Token> original = MediaSession.Token.CREATOR;
            creator.set(null, new Parcelable.Creator<MediaSession.Token>() {
                @Override public MediaSession.Token createFromParcel(Parcel source) {
                    MediaSession.Token token = original.createFromParcel(source);
                    try {
                        binder.set(token, wrap((IInterface) binder.get(token), true));
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException("Could not attribute a received media controller", error);
                    }
                    return token;
                }
                @Override public MediaSession.Token[] newArray(int size) { return original.newArray(size); }
            });
            installed = true;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Media controller attribution is unavailable", error);
        }
    }

    public static Object wrapSession(Object session) { return wrap((IInterface) session, false); }

    private static IInterface wrap(IInterface target, boolean controller) {
        if (target == null) return null;
        if (Proxy.isProxyClass(target.getClass()) && Proxy.getInvocationHandler(target) instanceof Calls) return target;
        try {
            Class<?> contract = Class.forName("android.media.session." + (controller ? "ISessionController" : "ISession"));
            return (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                    new Calls(target, controller));
        } catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
    }

    private static final class Calls implements InvocationHandler {
        private final IInterface target;
        private final boolean controller;
        Calls(IInterface target, boolean controller) { this.target = target; this.controller = controller; }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object[] forwarded = args;
            String name = method.getName();
            String guest = BActivityThread.getAppPackageName();
            if (controller && CALLER_METHODS.contains(name) && args != null && args.length > 0
                    && guest != null && guest.equals(args[0])) {
                forwarded = args.clone();
                forwarded[0] = BlackBoxCore.getHostPkg();
                if (("adjustVolume".equals(name) || "setVolumeTo".equals(name)) && args.length > 1
                        && guest.equals(args[1])) forwarded[1] = BlackBoxCore.getHostPkg();
            }
            try {
                Object result = method.invoke(target, forwarded);
                return !controller && "getController".equals(name) ? wrap((IInterface) result, true) : result;
            } catch (InvocationTargetException error) { throw error.getCause(); }
            // asBinder deliberately returns the original OS binder: ownership, equality,
            // parcel identity and system permission checks are not replaced by a local binder.
        }
    }
}
