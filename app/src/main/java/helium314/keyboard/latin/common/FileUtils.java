/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.common;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * A simple class to help with removing directories recursively.
 */
public class FileUtils {

    public static boolean deleteRecursively(final File path) {
        if (path.isDirectory()) {
            final File[] files = path.listFiles();
            if (files != null) {
                for (final File child : files) {
                    deleteRecursively(child);
                }
            }
        }
        return path.delete();
    }

    public static boolean deleteFilteredFiles(final File dir, final FilenameFilter fileNameFilter) {
        if (!dir.isDirectory()) {
            return false;
        }
        final File[] files = dir.listFiles(fileNameFilter);
        if (files == null) {
            return false;
        }
        boolean hasDeletedAllFiles = true;
        for (final File file : files) {
            if (!deleteRecursively(file)) {
                hasDeletedAllFiles = false;
            }
        }
        return hasDeletedAllFiles;
    }

    /** Largest file taken from another app's link (before anything is checked): 64 MiB, above the biggest dictionary files (a few tens of MB at most). */
    public static final long MAX_EXTERNAL_IMPORT_BYTES = 64L * 1024 * 1024;

    /** How long an access to a provider may take before it is given up (a provider that stalls must not hold the screen for ever). */
    public static final long COPY_TIMEOUT_MILLIS = 60_000L;

    /** The result of an import or a write, on the main thread: null when it is done, otherwise why it is not (no file of the import is left then). */
    public interface ImportCallback {
        void onResult(IOException failure);
    }

    /** The result of a read, on the main thread: [failure] null and the [data] and the provider's [displayName] (null when it has none), or the failure. */
    public interface ReadCallback {
        void onResult(IOException failure, byte[] data, String displayName);
    }

    /** Lets the screen that started a job give it up. */
    public interface ImportHandle {
        /**
         * Gives the job up (main thread): the callback is not called, and neither the temporary file nor the target file of an import is left,
         * whether the copy is running or its result is still waiting for the main thread. After the callback was called it changes nothing.
         */
        void cancel();
    }

    // One background thread for all provider jobs and one timer thread: a provider that stalls holds the one worker (the running flag
    // refuses a second job meanwhile), so stalled providers cannot pile threads up. Both are daemons and start on first use.
    private static final ExecutorService IMPORT_EXECUTOR = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            runnable -> daemon(runnable, "taen-import"));
    private static final ScheduledThreadPoolExecutor IMPORT_TIMER = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "taen-import-timer"));
    private static final AtomicBoolean IMPORT_RUNNING = new AtomicBoolean(false);

    static {
        IMPORT_TIMER.setRemoveOnCancelPolicy(true);
    }

    private static Thread daemon(final Runnable runnable, final String name) {
        final Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** True while the worker of an earlier job has not ended (it can outlive its timeout when the provider ignores a close). */
    public static boolean isImportRunning() {
        return IMPORT_RUNNING.get();
    }

    /**
     * Copies what [uri] provides to [outfile] on a background thread; the caller (any screen, on the main thread) never waits. [callback]
     * is called once on the main thread. The data goes to a temporary file that is renamed to [outfile] only when the copy finished in
     * time and within [maxBytes]; a failure, a timeout after [timeoutMillis], a stream longer than [maxBytes] or a cancel leaves neither the
     * temporary file nor [outfile] (a file that was there before the call is the caller's and is not touched). One job runs at a time:
     * while an earlier one has not ended, a new one fails at once ("another import is running").
     */
    public static ImportHandle importContentUri(final Uri uri, final Context context, final File outfile, final long maxBytes,
            final long timeoutMillis, final ImportCallback callback) {
        return start(new ImportJob(MODE_FILE, uri, context.getContentResolver(), outfile, null, maxBytes, false,
                (failure, data, name) -> callback.onResult(failure)), timeoutMillis);
    }

    /** As above with the 64 MiB cap and the 60 s limit. */
    public static ImportHandle importContentUri(final Uri uri, final Context context, final File outfile, final ImportCallback callback) {
        return importContentUri(uri, context, outfile, MAX_EXTERNAL_IMPORT_BYTES, COPY_TIMEOUT_MILLIS, callback);
    }

    /**
     * Reads what [uri] provides into memory (no file is written: for data that must not exist in the clear on disk) and the display name
     * of the document, on the same worker, with the same cap, time limit, one-at-a-time rule and cancel as [importContentUri].
     */
    public static ImportHandle readContentUri(final Uri uri, final Context context, final long maxBytes, final long timeoutMillis,
            final ReadCallback callback) {
        return start(new ImportJob(MODE_READ, uri, context.getContentResolver(), null, null, maxBytes, true, callback::onResult), timeoutMillis);
    }

    /** Writes [data] to the document [uri] (a target the user picked) on the same worker, with the same time limit and rules. */
    public static ImportHandle writeContentUri(final Uri uri, final Context context, final byte[] data, final long timeoutMillis,
            final ImportCallback callback) {
        return start(new ImportJob(MODE_WRITE, uri, context.getContentResolver(), null, data, data.length, false,
                (failure, unused, name) -> callback.onResult(failure)), timeoutMillis);
    }

    private static ImportHandle start(final ImportJob job, final long timeoutMillis) {
        if (!IMPORT_RUNNING.compareAndSet(false, true)) {
            job.failBeforeStart(new IOException("another import is running"));
            return job;
        }
        job.timeout = IMPORT_TIMER.schedule(job::onTimeout, timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            IMPORT_EXECUTOR.execute(job::run);
        } catch (RejectedExecutionException e) {
            IMPORT_RUNNING.set(false);
            job.timeout.cancel(false);
            job.failBeforeStart(new IOException("could not start the import"));
        }
        return job;
    }

    private static final int MODE_FILE = 0, MODE_READ = 1, MODE_WRITE = 2;

    private interface JobCallback {
        void onResult(IOException failure, byte[] data, String name);
    }

    /**
     * One job. Everything that decides its fate (state, stream, worker thread) is read and written under the monitor of the job, so the
     * worker's rename or commit, the timeout and a cancel exclude each other: the commit happens only while the state is RUNNING, and the
     * timeout or the cancel that comes first makes the state final, so a copy that ends later is deleted and never renamed.
     */
    private static final class ImportJob implements ImportHandle {
        private static final int RUNNING = 0, COMMITTED = 1, FAILED = 2, CANCELLED = 3;

        private final int mode;
        private final Uri uri;
        private final ContentResolver resolver;
        private final File outfile;
        private final File part;
        private final long maxBytes;
        private final boolean wantName;
        private final JobCallback callback;
        private int state = RUNNING;
        private IOException failure;
        private byte[] data; // the bytes to write (MODE_WRITE), later the bytes read (MODE_READ)
        private String name;
        private Closeable stream;
        private Thread worker;
        private boolean posted;
        private boolean delivered;
        volatile ScheduledFuture<?> timeout;

        ImportJob(final int mode, final Uri uri, final ContentResolver resolver, final File outfile, final byte[] data, final long maxBytes,
                final boolean wantName, final JobCallback callback) {
            this.mode = mode;
            this.uri = uri;
            this.resolver = resolver;
            this.outfile = outfile;
            this.part = outfile == null ? null : new File(outfile.getPath() + "." + System.nanoTime() + ".part");
            this.data = data;
            this.maxBytes = maxBytes;
            this.wantName = wantName;
            this.callback = callback;
        }

        synchronized void failBeforeStart(final IOException e) {
            state = FAILED;
            failure = e;
            data = null;
            post();
        }

        void run() {
            try {
                Thread.interrupted(); // a flag left by an earlier job's cancel must not fail this one
                synchronized (this) {
                    if (state != RUNNING) return;
                    worker = Thread.currentThread();
                }
                String displayName = wantName ? queryName() : null;
                byte[] result = null;
                if (mode == MODE_WRITE) {
                    final OutputStream out = resolver.openOutputStream(uri);
                    register(out);
                    if (out == null) throw new IOException("no stream");
                    try (OutputStream o = out) {
                        o.write(data);
                        o.flush();
                    }
                } else {
                    final InputStream in = resolver.openInputStream(uri);
                    register(in);
                    if (mode == MODE_FILE) copyStreamToNewFile(in, part, maxBytes);
                    else result = readAll(in, maxBytes);
                }
                synchronized (this) {
                    if (state == RUNNING) {
                        if (mode == MODE_FILE && !part.renameTo(outfile)) {
                            state = FAILED;
                            failure = new IOException("could not store the copy");
                        } else {
                            state = COMMITTED;
                            data = result;
                            name = displayName;
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    if (state == RUNNING) {
                        state = FAILED;
                        failure = e instanceof IOException ? (IOException) e : new IOException("could not copy from uri");
                    }
                }
            } finally {
                if (part != null) part.delete(); // gone after a rename; after any failure, timeout or cancel it must not stay
                synchronized (this) {
                    worker = null;
                    post();
                }
                IMPORT_RUNNING.set(false);
            }
        }

        /** Makes the stream known to the timeout and the cancel, or closes it at once when one of them has already decided. */
        private void register(final Closeable closeable) throws IOException {
            synchronized (this) {
                if (state == RUNNING) {
                    stream = closeable;
                    return;
                }
            }
            closeQuietly(closeable);
            throw new IOException("stopped");
        }

        private String queryName() {
            try (Cursor c = resolver.query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            } catch (RuntimeException ignored) { }
            return null;
        }

        /** Timer thread: the job took too long. Does nothing when it already finished, failed or was cancelled. */
        synchronized void onTimeout() {
            if (state != RUNNING) return;
            state = FAILED;
            failure = new IOException("timed out copying from uri");
            data = null;
            stop();
            post();
        }

        @Override
        public synchronized void cancel() {
            if (delivered || state == CANCELLED) return;
            if (state == RUNNING) stop();
            else if (state == COMMITTED && outfile != null) outfile.delete();
            state = CANCELLED;
            data = null;
            if (timeout != null) timeout.cancel(false);
        }

        /** Ends what a running job holds: closes the stream (that unblocks a read), interrupts the worker, removes the temporary file. */
        private void stop() {
            closeQuietly(stream);
            if (worker != null) worker.interrupt();
            if (part != null) part.delete();
        }

        /** Queues the one answer for the main thread (the timeout or the worker, whichever comes first). */
        private void post() {
            if (posted) return;
            posted = true;
            if (timeout != null) timeout.cancel(false);
            new Handler(Looper.getMainLooper()).post(this::deliver);
        }

        private void deliver() {
            final IOException resultFailure;
            final byte[] resultData;
            final String resultName;
            synchronized (this) {
                delivered = true;
                if (state == CANCELLED) return; // cancel() has already removed the files
                resultFailure = state == COMMITTED ? null : failure;
                resultData = state == COMMITTED ? data : null;
                resultName = state == COMMITTED ? name : null;
                data = null;
            }
            callback.onResult(resultFailure, resultData, resultName);
        }

        private static void closeQuietly(final Closeable c) {
            if (c == null) return;
            try {
                c.close();
            } catch (IOException ignored) { }
        }
    }

    /** All of [in] (closed), at most [maxBytes]: a longer stream fails, as does an interrupt. */
    private static byte[] readAll(final InputStream in, final long maxBytes) throws IOException {
        try (InputStream input = in) {
            if (input == null) throw new IOException("no stream");
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            long total = 0;
            int len;
            while ((len = input.read(buf)) > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                total += len;
                if (total > maxBytes) throw new IOException("file too large");
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        }
    }

    public static void copyStreamToNewFile(final InputStream in, final File outfile) throws IOException {
        copyStreamToNewFile(in, outfile, Long.MAX_VALUE);
    }

    public static void copyStreamToNewFile(final InputStream in, final File outfile, final long maxBytes) throws IOException {
        File parentFile = outfile.getParentFile();
        if (parentFile == null || (!parentFile.exists() && !parentFile.mkdirs())) {
            throw new IOException("could not create parent folder");
        }
        try (FileOutputStream out = new FileOutputStream(outfile); InputStream input = in) {
            if (input == null) throw new IOException("no stream");
            long total = 0;
            final byte[] buf = new byte[8192];
            int len;
            while ((len = input.read(buf)) > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                total += len;
                if (total > maxBytes) throw new IOException("file too large");
                out.write(buf, 0, len);
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            outfile.delete();
            throw e;
        }
    }

    public static void copyStreamToOtherStream(final InputStream in, final OutputStream out) throws IOException {
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        out.flush();
    }

}
