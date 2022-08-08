/***************************************************************************************
 * Copyright (c) 2015 Timothy Rae <perceptualchaos2@gmail.com>                          *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.text.format.Formatter;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ichi2.anki.exception.StorageAccessException;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Storage;
import com.ichi2.libanki.exception.UnknownDatabaseVersionException;
import com.ichi2.libanki.utils.SystemTime;
import com.ichi2.libanki.utils.Time;
import com.ichi2.libanki.utils.TimeManager;
import com.ichi2.preferences.PreferenceExtensions;
import com.ichi2.utils.FileUtil;

import net.ankiweb.rsdroid.Backend;
import net.ankiweb.rsdroid.BackendException;
import net.ankiweb.rsdroid.BackendFactory;

import java.io.File;
import java.io.IOException;

import androidx.annotation.VisibleForTesting;
import timber.log.Timber;

import static com.ichi2.libanki.BackendImportExportKt.importCollectionPackage;

/**
 * Singleton which opens, stores, and closes the reference to the Collection.
 */
public class CollectionHelper {
    // Name of anki2 file
    public static final String COLLECTION_FILENAME = "collection.anki2";

    /**
     * The preference key for the path to the current AnkiDroid directory
     * <br>
     * This directory contains all AnkiDroid data and media for a given collection
     * Except the Android preferences, cached files and {@link MetaDB}
     * <br>
     * This can be changed by the {@link Preferences} screen
     * to allow a user to access a second collection via the same AnkiDroid app instance.
     * <br>
     * The path also defines the collection that the AnkiDroid API accesses
     */
    public static final String PREF_COLLECTION_PATH = "deckPath";

    /**
     * Prevents {@link com.ichi2.async.CollectionLoader} from spuriously re-opening the {@link Collection}.
     *
     * <p>Accessed only from synchronized methods.
     */
    private boolean mCollectionLocked;

    /**
     * If the last call to getColSafe() failed, this stores the error type. This only exists
     * to enable better error reporting during startup; in the future it would be better if
     * callers check the exception themselves via a helper routine, instead of relying on a null
     * return.
     */
    private static @Nullable CollectionOpenFailure mLastOpenFailure;

    @Nullable
    public static Long getCollectionSize(Context context) {
        try {
            String path = getCollectionPath(context);
            return new File(path).length();
        } catch (Exception e) {
            Timber.e(e, "Error getting collection Length");
            return null;
        }
    }

    public synchronized void lockCollection() {
        Timber.i("Locked Collection - Collection Loading should fail");
        mCollectionLocked = true;
    }
    public synchronized void unlockCollection() {
        Timber.i("Unlocked Collection");
        mCollectionLocked = false;
    }
    public synchronized boolean isCollectionLocked() {
        return mCollectionLocked;
    }


    /**
     * If the last call to getColSafe() failed, this contains the error type.
     */
    @Nullable
    public static CollectionOpenFailure getLastOpenFailure() {
        return mLastOpenFailure;
    }

    /**
     * Lazy initialization holder class idiom. High performance and thread safe way to create singleton.
     */
    @VisibleForTesting
    public static class LazyHolder {
        @VisibleForTesting
        public static CollectionHelper INSTANCE = new CollectionHelper();
    }

    /**
     * @return Singleton instance of the helper class
     */
    public static CollectionHelper getInstance() {
        return LazyHolder.INSTANCE;
    }

    /**
     * Opens the collection without checking to see if the directory exists.
     *
     * path should be tested with File.exists() and File.canWrite() before this is called
     */
    private Collection openCollection(Context context, String path) {
        Timber.i("Begin openCollection: %s", path);
        Backend backend = BackendFactory.getBackend(context);
        Collection collection = Storage.collection(context, path, false, true, backend);
        Timber.i("End openCollection: %s", path);
        return collection;
    }

    /**
     * Get the single instance of the {@link Collection}, creating it if necessary  (lazy initialization).
     * @param _context is no longer used, as the global AnkidroidApp instance is used instead
     * @return instance of the Collection
     */
    public synchronized Collection getCol(Context context) {
        return CollectionManager.getColUnsafe();
    }

    /**
     * Given a path to a .anki2 file returns an open {@link Collection} associated with the path.
     *
     * This operation does not call {@link #initializeAnkiDroidDirectory} and does not set the singleton instance's {@link #mCollection}
     *
     * @param path The path to collection.anki2
     * @return An open {@link Collection} object
     *
     * @throws StorageAccessException the file at `path` is not writable
     * @throws StorageAccessException `path` does not exist
     */
    public Collection getColFromPath(String path, Context context) throws StorageAccessException {
        File f = new File(path);

        if (!f.exists()) {
            throw new StorageAccessException(path + " does not exist");
        }

        if (!f.canWrite()) {
            throw new StorageAccessException(path + " is not writable");
        }

        return openCollection(context, path);
    }

    /** Collection time if possible, otherwise real time.*/
    public synchronized Time getTimeSafe(Context context) {
        try {
            return TimeManager.INSTANCE.getTime();
        } catch (Exception e) {
            Timber.w(e);
            return new SystemTime();
        }
    }

    /**
     * Call getCol(context) inside try / catch statement.
     * Send exception report and return null if there was an exception.
     * @param context
     * @return
     */
    public synchronized Collection getColSafe(Context context) {
        mLastOpenFailure = null;
        try {
            return getCol(context);
        } catch (BackendException.BackendDbException.BackendDbLockedException e) {
            mLastOpenFailure = CollectionOpenFailure.LOCKED;
            Timber.w(e);
            return null;
        } catch (BackendException.BackendDbException.BackendDbFileTooNewException e) {
            mLastOpenFailure = CollectionOpenFailure.FILE_TOO_NEW;
            Timber.w(e);
            return null;
        } catch (Exception e) {
            mLastOpenFailure = CollectionOpenFailure.CORRUPT;
            Timber.w(e);
            CrashReportService.sendExceptionReport(e, "CollectionHelper.getColSafe");
            return null;
        }
    }

    /**
     * Close the {@link Collection}, optionally saving
     * @param save whether or not save before closing
     */
    public synchronized void closeCollection(boolean save, String reason) {
        Timber.i("closeCollection: %s", reason);
        CollectionManager.closeCollectionBlocking(save);
    }


    /**
     * @return Whether or not {@link Collection} and its child database are open.
     */
    public boolean colIsOpen() {
        return CollectionManager.isOpenUnsafe();
    }

    /**
     * Create the AnkiDroid directory if it doesn't exist and add a .nomedia file to it if needed.
     *
     * The AnkiDroid directory is a user preference stored under {@link #PREF_COLLECTION_PATH}, and a sensible
     * default is chosen if the preference hasn't been created yet (i.e., on the first run).
     *
     * The presence of a .nomedia file indicates to media scanners that the directory must be
     * excluded from their search. We need to include this to avoid media scanners including
     * media files from the collection.media directory. The .nomedia file works at the directory
     * level, so placing it in the AnkiDroid directory will ensure media scanners will also exclude
     * the collection.media sub-directory.
     *
     * @param path  Directory to initialize
     * @throws StorageAccessException If no write access to directory
     */
    public static synchronized void initializeAnkiDroidDirectory(String path) throws StorageAccessException {
        // Create specified directory if it doesn't exit
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new StorageAccessException("Failed to create AnkiDroid directory " + path);
        }
        if (!dir.canWrite()) {
            throw new StorageAccessException("No write access to AnkiDroid directory " + path);
        }
        // Add a .nomedia file to it if it doesn't exist
        File nomedia = new File(dir, ".nomedia");
        if (!nomedia.exists()) {
            try {
                nomedia.createNewFile();
            } catch (IOException e) {
                throw new StorageAccessException("Failed to create .nomedia file", e);
            }
        }
    }

    /**
     * Try to access the current AnkiDroid directory
     * @return whether or not dir is accessible
     * @param context to get directory with
     */
    public static boolean isCurrentAnkiDroidDirAccessible(Context context) {
        try {
            initializeAnkiDroidDirectory(getCurrentAnkiDroidDirectory(context));
            return true;
        } catch (StorageAccessException e) {
            Timber.w(e);
            return false;
        }
    }


    /**
     * Get the absolute path to a directory that is suitable to be the default starting location
     * for the AnkiDroid directory.
     * <p>
     * Currently, this is a directory named "AnkiDroid" at the top level of the non-app-specific external storage directory.
     * <p><br>
     * When targeting API > 29, AnkiDroid will have to use Scoped Storage on any device of any API level.
     * Scoped Storage only allows access to App-Specific directories (without permissions).
     * Hence, AnkiDroid won't be able to access the directory used currently on all devices,
     * regardless of their API level, once AnkiDroid targets API > 29.
     * Instead, AnkiDroid will have to use an App-Specific directory to store the AnkiDroid directory.
     * This applies to the entire AnkiDroid userbase.
     * <p><br>
     * Currently, if <code>TESTING_SCOPED_STORAGE</code> is set to <code>true</code>, AnkiDroid uses its External
     * App-Specific directory.<p>
     * External App-Specific Storage is used since the only advantage Internal App-Specific Storage has over External
     * App-Specific storage is additional security, but AnkiDroid does not store sensitive data. Defaulting to
     * External Storage preserves the current behavior of the App
     * (AnkiDroid defaults to External before the Migration To Scoped Storage).
     * <p>
     * TODO: If External Storage isn't emulated, allow users to choose between External & Internal App-Specific Storage
     *  instead of defaulting to External App-Specific Storage. This should be done since using either one may be more
     *  useful for them. If External Storage is emulated, there is no use in providing the option since Internal
     *  Storage can not provide more storage space than External Storage if External Storage is emulated.
     * <p><br>
     * See the detailed explanation on storage locations & their classification below for more details.
     * <p><br>
     * App-Specific storage refers to directories which are meant to store files that are meant to be used by a
     * particular app. Each app has its own Internal & External App-Specific directory. Under Scoped Storage,
     * an app can only access its own Internal & External App-Specific directory without needing permissions.
     * <p><br>
     * Storage can be classified as Internal or External Storage. <p><br>
     * Internal Storage: This storage is characterized by the fact that it is always available since it always resides
     * on the device's own non-removable storage.<p>
     * App-Specific Internal Storage can be accessed by ONLY the app which owns that directory (without any permissions).
     * It cannot be accessed by any other apps.
     * It cannot be accessed using the Files app on Android or by connecting a device to a pc via USB.
     * <p><br>
     * External Storage: <p>
     * This storage is characterized only by the fact that it is not guaranteed to be available.<p>
     * It may be built-in, non-removable storage on the device which is being emulated to function like external storage.
     * In this case, it doesn't offer more space than Internal Storage.<p>
     * Or, it may be removable storage like an SD Card.<p>
     * App-Specific External Storage can be accessed by the app it is owned by without any permissions.
     * It can be accessed by any apps with the WRITE_EXTERNAL_STORAGE permission.
     * It can also be accessed via the Android Files app or by connecting the device to a PC via USB.
     * <p><br>
     * Note: The Files app can be misleading. On Samsung devices, clicking on Internal Storage it actually shows the
     * emulated external storage (/storage/emulated/0/ in my case) - this is because from the point of view of the user,
     * emulated external storage is just more internal storage since it is built into the phone. This is why vendors
     * like Samsung may refer to external emulated storage as internal storage, even though for developers, they mean
     * very different things as explained above.
     * <p><br>
     *
     * @return Absolute Path to the default location starting location for the AnkiDroid directory
     */
    @SuppressWarnings("deprecation") // TODO Tracked in https://github.com/ankidroid/Anki-Android/issues/5304
    @CheckResult
    public static String getDefaultAnkiDroidDirectory(@NonNull Context context) {
        if (AnkiDroidApp.TESTING_SCOPED_STORAGE) {
            return getAppSpecificExternalAnkiDroidDirectory(context);
        }
        return getLegacyAnkiDroidDirectory();
    }


    /**
     * Returns the absolute path to the AnkiDroid directory under the primary/shared external storage directory.
     * This directory may be in emulated external storage, or can be an SD Card directory.
     * <p>
     * The path returned will no longer be accessible to AnkiDroid once targetSdk > 29
     *
     * @return Absolute path to the AnkiDroid directory in primary shared/external storage
     */
    @SuppressWarnings("deprecation")
    public static String getLegacyAnkiDroidDirectory() {
        return new File(Environment.getExternalStorageDirectory(), "AnkiDroid").getAbsolutePath();
    }


    /**
     * Returns the absolute path to the AnkiDroid directory under the app-specific, primary/shared external storage
     * directory.
     * <p>
     * This directory may be in emulated external storage, or can be an SD Card directory.
     * If it is actually external storage, i.e., removable storage like an SD Card, instead of storage
     * built into the device itself, using this directory over internal storage can be beneficial since
     * it may be able to store more data.
     * <p>
     * AnkiDroid can access this directory without permissions, even under Scoped Storage
     * Other apps can access this directory if they have the WRITE_EXTERNAL_STORAGE permission
     *
     * @param context Used to get the External App-Specific directory for AnkiDroid
     * @return Returns the absolute path to the App-Specific External AnkiDroid directory
     */
    public static String getAppSpecificExternalAnkiDroidDirectory(@NonNull Context context) {
        return context.getExternalFilesDir(null).getAbsolutePath();
    }


    /**
     * @return Returns an array of {@link File}s reflecting the directories that AnkiDroid can access without storage permissions
     * @see android.content.Context#getExternalFilesDirs(String)
     */
    @NonNull
    public static File[] getAppSpecificExternalDirectories(@NonNull Context context) {
        return context.getExternalFilesDirs(null);
    }

    /**
     * Returns the absolute path to the private AnkiDroid directory under the app-specific, internal storage directory.
     * <p>
     * AnkiDroid can access this directory without permissions, even under Scoped Storage
     * Other apps cannot access this directory, regardless of what permissions they have
     *
     * @param context Used to get the Internal App-Specific directory for AnkiDroid
     * @return Returns the absolute path to the App-Specific Internal AnkiDroid Directory
     */
    public static String getAppSpecificInternalAnkiDroidDirectory(@NonNull Context context) {
        return context.getFilesDir().getAbsolutePath();
    }

    /**
     *
     * @return the path to the actual {@link Collection} file
     */
    public static @NonNull String getCollectionPath(Context context) {
        return new File(getCurrentAnkiDroidDirectory(context), COLLECTION_FILENAME).getAbsolutePath();
    }

    /**
     * @return the absolute path to the AnkiDroid directory.
     */
    public static String getCurrentAnkiDroidDirectory(Context context) {
        SharedPreferences preferences = AnkiDroidApp.getSharedPrefs(context);
        if (AnkiDroidApp.INSTRUMENTATION_TESTING) {
            // create an "androidTest" directory inside the current collection directory which contains the test data
            // "/AnkiDroid/androidTest" would be a new collection path
            return new File(getDefaultAnkiDroidDirectory(context), "androidTest").getAbsolutePath();
        }
        return PreferenceExtensions.getOrSetString(
                preferences,
                PREF_COLLECTION_PATH,
                () -> getDefaultAnkiDroidDirectory(context));
    }

    /**
     * Get parent directory given the {@link Collection} path.
     * @param path path to AnkiDroid collection
     * @return path to AnkiDroid directory
     */
    private static String getParentDirectory(String path) {
        return new File(path).getParentFile().getAbsolutePath();
    }

    /**
     * This currently stores either:
     * An error message stating the reason that a storage check must be performed
     * OR
     * The current storage requirements, and the current available storage.
     */
    public static class CollectionIntegrityStorageCheck {

        @Nullable
        private final String mErrorMessage;

        //OR:
        @Nullable
        private final Long mRequiredSpace;
        @Nullable
        private final Long mFreeSpace;

        private CollectionIntegrityStorageCheck(long requiredSpace, long freeSpace) {
            this.mFreeSpace = freeSpace;
            this.mRequiredSpace = requiredSpace;
            this.mErrorMessage = null;
        }

        private CollectionIntegrityStorageCheck(@NonNull String errorMessage) {
            this.mRequiredSpace = null;
            this.mFreeSpace = null;
            this.mErrorMessage = errorMessage;
        }

        private static CollectionIntegrityStorageCheck fromError(String errorMessage) {
            return new CollectionIntegrityStorageCheck(errorMessage);
        }

        private static String defaultRequiredFreeSpace(Context context) {
            long oneHundredFiftyMB = 150 * 1000 * 1000; //tested, 1024 displays 157MB. 1000 displays 150
            return Formatter.formatShortFileSize(context, oneHundredFiftyMB);
        }

        public static CollectionIntegrityStorageCheck createInstance(Context context) {

            Long maybeCurrentCollectionSizeInBytes = getCollectionSize(context);
            if (maybeCurrentCollectionSizeInBytes == null) {
                Timber.w("Error obtaining collection file size.");
                String requiredFreeSpace = defaultRequiredFreeSpace(context);
                return fromError(context.getResources().getString(R.string.integrity_check_insufficient_space, requiredFreeSpace));
            }

            // This means that when VACUUMing a database, as much as twice the size of the original database file is
            // required in free disk space. - https://www.sqlite.org/lang_vacuum.html
            long requiredSpaceInBytes = maybeCurrentCollectionSizeInBytes * 2;

            // We currently use the same directory as the collection for VACUUM/ANALYZE due to the SQLite APIs
            File collectionFile = new File(getCollectionPath(context));
            long freeSpace = FileUtil.getFreeDiskSpace(collectionFile, -1);

            if (freeSpace == -1) {
                Timber.w("Error obtaining free space for '%s'", collectionFile.getPath());
                String readableFileSize  = Formatter.formatFileSize(context, requiredSpaceInBytes);
                return fromError(context.getResources().getString(R.string.integrity_check_insufficient_space, readableFileSize));
            }

            return new CollectionIntegrityStorageCheck(requiredSpaceInBytes, freeSpace);
        }

        public boolean shouldWarnOnIntegrityCheck() {
            return this.mErrorMessage != null || fileSystemDoesNotHaveSpaceForBackup();
        }

        private boolean fileSystemDoesNotHaveSpaceForBackup() {
            //only to be called when mErrorMessage == null
            if (mFreeSpace == null || mRequiredSpace == null) {
                Timber.e("fileSystemDoesNotHaveSpaceForBackup called in invalid state.");
                return true;
            }
            Timber.d("Required Free Space: %d. Current: %d", mRequiredSpace, mFreeSpace);
            return mRequiredSpace > mFreeSpace;
        }


        public String getWarningDetails(Context context) {
            if (mErrorMessage != null) {
                return mErrorMessage;
            }
            if (mFreeSpace == null || mRequiredSpace == null) {
                Timber.e("CollectionIntegrityCheckStatus in an invalid state");
                String defaultRequiredFreeSpace = defaultRequiredFreeSpace(context);
                return context.getResources().getString(R.string.integrity_check_insufficient_space, defaultRequiredFreeSpace);
            }

            String required = Formatter.formatShortFileSize(context, mRequiredSpace);
            String insufficientSpace = context.getResources().getString(
                    R.string.integrity_check_insufficient_space, required);

            //Also concat in the extra content showing the current free space.
            String currentFree = Formatter.formatShortFileSize(context, mFreeSpace);
            String insufficientSpaceCurrentFree = context.getResources().getString(
                    R.string.integrity_check_insufficient_space_extra_content, currentFree);
            return insufficientSpace + insufficientSpaceCurrentFree;
        }
    }

    /** Fetches additional collection data not required for
     * application startup
     *
     * Allows mandatory startup procedures to return early, speeding up startup. Less important tasks are offloaded here
     * No-op if data is already fetched
     */
    public static void loadCollectionComplete(Collection col) {
        col.getModels();
    }

    public static int getDatabaseVersion(Context context) throws UnknownDatabaseVersionException {
        // backend can't open a schema version outside range, so fall back to a pure DB implementation
        return Storage.getDatabaseVersion(context, getCollectionPath(context));
    }

    public enum DatabaseVersion {
        USABLE,
        FUTURE_NOT_DOWNGRADABLE,
        UNKNOWN
    }

    public enum CollectionOpenFailure {
        FILE_TOO_NEW,
        CORRUPT,
        LOCKED
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public void setColForTests(Collection col) {
        CollectionManager.setColForTests(col);
    }
}
