package top.niunaijun.blackbox.utils.compat;

import android.os.Bundle;
import android.os.IBinder;

/** Private same-UID provider startup protocol; never uses a cached process listing. */
public final class ProcessInitializationReply {
    private static final String CLIENT = "_Black_|_client_";
    private static final String PID = "_VirtualDAP_|_pid_";

    private ProcessInitializationReply() {}

    public static Bundle create(IBinder client, int pid) {
        if (client == null || !client.isBinderAlive() || pid <= 0) {
            throw new IllegalArgumentException("A live client and positive process ID are required");
        }
        Bundle reply = new Bundle();
        reply.putBinder(CLIENT, client);
        reply.putInt(PID, pid);
        return reply;
    }

    public static IBinder client(Bundle reply) {
        return reply == null ? null : reply.getBinder(CLIENT);
    }

    public static int pid(Bundle reply, int serverPid) {
        IBinder client = client(reply);
        if (client == null || !client.isBinderAlive()) return 0;
        int pid = reply.getInt(PID, 0);
        // Never let a malformed/old reply make rollback kill the control process.
        return pid > 0 && pid != serverPid ? pid : 0;
    }
}
