package net.kdt.pojavlaunch.coint.auth;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.io.IOException;
import java.util.concurrent.*;

/** Session work survives rotation; views receive only display state, never tokens. */
public final class CointAccountModel extends AndroidViewModel {
    public static final class State {
        public final boolean busy;
        public final String username, error;
        public final boolean verified;
        State(boolean busy, String username, boolean verified, String error) {
            this.busy = busy; this.username = username; this.verified = verified; this.error = error;
        }
    }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(true, null, false, null));
    private final CointSessionStore store;
    private GravitAuth.Session session;
    private volatile GravitSocket active;
    private volatile boolean cleared;
    private boolean busy = true;

    public CointAccountModel(@NonNull Application application) {
        super(application);
        store = new CointSessionStore(application);
        worker.execute(() -> {
            try { session = store.load(); publish(false, null); }
            catch (IOException invalid) { publish(false, "storage"); }
        });
    }
    public LiveData<State> state() { return state; }

    public synchronized void login(String login, String password) {
        if (busy || cleared) return;
        busy = true;
        state.setValue(new State(true, session == null ? null : session.username, false, null));
        worker.execute(() -> {
            try (GravitSocket socket = new GravitSocket()) {
                active = socket;
                GravitAuth.Session result = new GravitAuth(socket).login(login, password, System.currentTimeMillis());
                if (cleared) return;
                store.save(result);
                session = result;
                publish(true, null);
            } catch (IOException error) { publish(false, code(error)); }
            catch (RuntimeException invalid) { publish(false, "protocol"); }
            finally { active = null; }
        });
    }
    public synchronized void restore() {
        if (busy || cleared || session == null) return;
        busy = true;
        state.setValue(new State(true, session.username, false, null));
        worker.execute(() -> {
            try (GravitSocket socket = new GravitSocket()) {
                active = socket;
                GravitAuth.Session restored = new GravitAuth(socket).restore(session, System.currentTimeMillis(), store::save);
                if (cleared) return;
                store.save(restored);
                session = restored;
                publish(true, null);
            } catch (IOException error) {
                // Keep a saved session for transient network errors; explicit logout removes it.
                try { session = store.load(); } catch (IOException ignored) { }
                publish(false, code(error));
            } catch (RuntimeException invalid) { publish(false, "protocol"); }
            finally { active = null; }
        });
    }
    public synchronized void logout() {
        if (busy || cleared) return;
        busy = true;
        state.setValue(new State(true, session == null ? null : session.username, false, null));
        worker.execute(() -> {
            try { store.clear(); session = null; publish(false, null); }
            catch (IOException error) { publish(false, "storage"); }
        });
    }
    private static String code(IOException error) {
        return error instanceof GravitAuth.Failure ? ((GravitAuth.Failure) error).code : "storage";
    }
    private synchronized void publish(boolean verified, String error) {
        busy = false;
        if (!cleared) state.postValue(new State(false, session == null ? null : session.username, verified, error));
    }
    @Override protected void onCleared() {
        cleared = true;
        GravitSocket socket = active;
        if (socket != null) socket.close();
        worker.shutdownNow();
    }
}
