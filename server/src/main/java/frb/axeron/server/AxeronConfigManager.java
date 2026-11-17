package frb.axeron.server;

import static frb.axeron.server.ServerConstants.PERMISSION;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.system.Os;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import frb.axeron.ktx.HandlerKt;
import frb.axeron.server.util.UserHandleCompat;
import frb.axeron.shared.AxeronApiConstant;
import frb.axeron.shared.PathHelper;
import kotlin.collections.ArraysKt;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.UserManagerApis;

public class AxeronConfigManager extends ConfigManager {

    private static final Gson GSON_IN = new GsonBuilder()
            .create();
    private static final Gson GSON_OUT = new GsonBuilder()
            .setVersion(AxeronConfig.LATEST_VERSION)
            .create();

    private static final long WRITE_DELAY = 500;

    private static final File FILE = PathHelper.getWorkingPath(Os.getuid() == 0, AxeronApiConstant.folder.PARENT + "ax_permission.json");
    private static final AtomicFile ATOMIC_FILE = new AtomicFile(FILE);
    private final AxeronConfig config;
    /** Uids whose entry was checked against the app on them, until the uid's processes are gone. */
    private final Set<Integer> verified = new HashSet<>();

    private final Runnable mWriteRunner = new Runnable() {

        @Override
        public void run() {
            synchronized (AxeronConfigManager.this) {
                write(config);
            }
        }
    };

    public AxeronConfigManager() {
        this.config = load();

        boolean changed = false;
        boolean pmReady = isPackageManagerReady();

        if (config.packages == null) {
            config.packages = new ArrayList<>();
            changed = true;
        }

        if (config.version < 3) {
            for (AxeronConfig.PackageEntry entry : config.packages) {
                entry.flags = entry.flags & ~ConfigManager.FLAG_DENIED;
            }
            LOGGER.i("config v%d -> v%d: cleared implicit denials", config.version, AxeronConfig.LATEST_VERSION);
            config.version = AxeronConfig.LATEST_VERSION;
            changed = true;
        }

        for (AxeronConfig.PackageEntry entry : new ArrayList<>(config.packages)) {
            if (entry.packages == null) {
                entry.packages = new ArrayList<>();
            }

            List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(entry.uid);
            if (packages.isEmpty()) {
                if (!pmReady) {
                    LOGGER.w("package manager not ready, keep config for uid %d", entry.uid);
                    continue;
                }
                LOGGER.i("remove config for uid %d since it has gone", entry.uid);
                config.packages.remove(entry);
                changed = true;
                continue;
            }

            if (entry.packages.isEmpty()) {
                entry.packages.addAll(packages);
                changed = true;
            }

            boolean packagesChanged = true;

            for (String packageName : entry.packages) {
                if (packages.contains(packageName)) {
                    packagesChanged = false;
                    break;
                }
            }

            final int rawSize = entry.packages.size();
            Set<String> s = new LinkedHashSet<>(entry.packages);
            entry.packages.clear();
            entry.packages.addAll(s);
            final int shrunkSize = entry.packages.size();
            if (shrunkSize < rawSize) {
                LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize);
            }

            if (packagesChanged) {
                LOGGER.i("remove config for uid %d since the packages for it changed", entry.uid);
                config.packages.remove(entry);
                changed = true;
            }
        }

        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            for (PackageInfo pi : PackageManagerApis.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS, userId)) {
                if (pi == null
                        || pi.applicationInfo == null
                        || pi.requestedPermissions == null
                        || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                int uid = pi.applicationInfo.uid;
                String pkg = pi.packageName;
                AxeronConfig.PackageEntry entry = findLocked(uid);
                boolean allowed = false;
                boolean denied = false;

                if (entry != null && entry.packages != null && entry.packages.contains(pkg)) {
                    allowed = entry.isAllowed();
                    denied = entry.isDenied();
                }

                List<String> packages = new ArrayList<>();
                packages.add(pkg);

                int flags = allowed ? ConfigManager.FLAG_ALLOWED : (denied ? ConfigManager.FLAG_DENIED : 0);
                updateLocked(uid, packages, ConfigManager.MASK_PERMISSION, flags);
                changed = true;
            }
        }

        if (changed) {
            scheduleWriteLocked();
        }

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "ax-config-flush"));
        } catch (Throwable tr) {
            LOGGER.w(tr, "add shutdown hook");
        }
    }

    private static boolean isPackageManagerReady() {
        try {
            for (int userId : UserManagerApis.getUserIdsNoThrow()) {
                if (!PackageManagerApis.getInstalledPackagesNoThrow(0, userId).isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable tr) {
            LOGGER.w(tr, "isPackageManagerReady");
        }
        return false;
    }

    /** Writes pending changes now instead of waiting for the delayed write. */
    public void flush() {
        synchronized (this) {
            HandlerKt.getWorkerHandler().removeCallbacks(mWriteRunner);
            write(config);
        }
    }

    public static AxeronConfig load() {
        FileInputStream stream;
        try {
            stream = ATOMIC_FILE.openRead();
        } catch (FileNotFoundException e) {
            LOGGER.i("no existing config file " + ATOMIC_FILE.getBaseFile() + "; starting empty");
            return new AxeronConfig();
        }

        AxeronConfig config = null;
        try {
            config = GSON_IN.fromJson(new InputStreamReader(stream), AxeronConfig.class);
        } catch (Throwable tr) {
            LOGGER.w(tr, "load config");
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                LOGGER.w("failed to close: " + e);
            }
        }
        if (config != null) return config;
        return new AxeronConfig();
    }

    public static void write(AxeronConfig config) {
        synchronized (ATOMIC_FILE) {
            FileOutputStream stream;
            try {
                stream = ATOMIC_FILE.startWrite();
            } catch (IOException e) {
                LOGGER.w("failed to write state: " + e);
                return;
            }

            try {
                String json = GSON_OUT.toJson(config);
                stream.write(json.getBytes());

                ATOMIC_FILE.finishWrite(stream);
                LOGGER.v("config saved");
            } catch (Throwable tr) {
                LOGGER.w(tr, "can't save %s, restoring backup.", ATOMIC_FILE.getBaseFile());
                ATOMIC_FILE.failWrite(stream);
            }
        }
    }

    private void scheduleWriteLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (HandlerKt.getWorkerHandler().hasCallbacks(mWriteRunner)) {
                return;
            }
        } else {
            HandlerKt.getWorkerHandler().removeCallbacks(mWriteRunner);
        }
        HandlerKt.getWorkerHandler().postDelayed(mWriteRunner, WRITE_DELAY);
    }

    private AxeronConfig.PackageEntry findLocked(int uid) {
        for (AxeronConfig.PackageEntry entry : config.packages) {
            if (uid == entry.uid) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    private String signatureOf(int uid, List<String> packages) {
        if (packages == null || packages.isEmpty()) return null;
        return AxeronService.certDigest(packages.get(0), UserHandleCompat.getUserId(uid));
    }

    @Nullable
    private AxeronConfig.PackageEntry findVerifiedLocked(int uid) {
        AxeronConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) return null;
        if (verified.contains(uid)) return entry;

        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(uid);
        String signature = signatureOf(uid, packages);
        if (signature == null) return null;

        boolean hasPkg = false;
        if (entry.packages != null) {
            for (String p : entry.packages) {
                if (packages.contains(p)) {
                    hasPkg = true;
                    break;
                }
            }
        }
        String stored = entry.signature;
        if (!hasPkg || (stored != null && !stored.equals(signature))) {
            LOGGER.w("remove config for uid %d since it isn't the app it was made for", uid);
            removeLocked(uid);
            return null;
        }
        if (stored == null) {
            entry.signature = signature;
            scheduleWriteLocked();
        }
        verified.add(uid);
        return entry;
    }

    @Nullable
    @Override
    public AxeronConfig.PackageEntry find(int uid) {
        synchronized (this) {
            return findVerifiedLocked(uid);
        }
    }

    public void invalidate(int uid) {
        synchronized (this) {
            verified.remove(uid);
        }
    }

    @Nullable
    public AxeronConfig.PackageEntry findOrUpdate(int uid) {
        synchronized (this) {
            AxeronConfig.PackageEntry entry = findVerifiedLocked(uid);
            if (entry == null) {
                entry = findLocked(uid);
            }
            if (entry == null) {
                entry = new AxeronConfig.PackageEntry(uid, 0);
                List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(uid);
                entry.packages.addAll(packages);
                entry.signature = signatureOf(uid, packages);
                config.packages.add(entry);
            }
            return entry;
        }
    }

    private void updateLocked(int uid, List<String> packages, int mask, int values) {
        if (packages == null) {
            packages = PackageManagerApis.getPackagesForUidNoThrow(uid);
        }

        boolean changed = false;
        AxeronConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            entry = new AxeronConfig.PackageEntry(uid, mask & values);
            entry.signature = signatureOf(uid, packages);
            config.packages.add(entry);
            changed = true;
        } else {
            if (entry.packages == null) {
                entry.packages = new ArrayList<>();
                changed = true;
            }
            if (entry.signature == null) {
                entry.signature = signatureOf(uid, packages);
                changed = true;
            }
            int newValue = (entry.flags & ~mask) | (mask & values);
            if (newValue != entry.flags) {
                entry.flags = newValue;
                changed = true;
            }
        }
        if (packages != null) {
            for (String packageName : packages) {
                if (packageName == null || entry.packages.contains(packageName)) {
                    continue;
                }
                entry.packages.add(packageName);
                changed = true;
            }
        }
        if (changed) {
            scheduleWriteLocked();
        }
    }

    public void update(int uid, List<String> packages, int mask, int values) {
        synchronized (this) {
            updateLocked(uid, packages, mask, values);
        }
    }

    private void removeLocked(int uid) {
        AxeronConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            return;
        }
        config.packages.remove(entry);
        verified.remove(uid);
        scheduleWriteLocked();
    }

    public void remove(int uid) {
        synchronized (this) {
            removeLocked(uid);
        }
    }
}
