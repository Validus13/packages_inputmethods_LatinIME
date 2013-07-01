/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.latin.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.android.inputmethod.latin.Constants;
import com.android.inputmethod.latin.LatinImeLogger;
import com.android.inputmethod.latin.SuggestedWords;
import com.android.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import com.android.inputmethod.latin.WordComposer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// TODO: Come up with a more descriptive class name
public final class Utils {
    private static final String TAG = Utils.class.getSimpleName();

    private Utils() {
        // This utility class is not publicly instantiable.
    }

    // TODO: Make this an external class
    public static final class UsabilityStudyLogUtils {
        // TODO: remove code duplication with ResearchLog class
        private static final String USABILITY_TAG = UsabilityStudyLogUtils.class.getSimpleName();
        private static final String FILENAME = "log.txt";
        private final Handler mLoggingHandler;
        private File mFile;
        private File mDirectory;
        private InputMethodService mIms;
        private PrintWriter mWriter;
        private final Date mDate;
        private final SimpleDateFormat mDateFormat;

        private UsabilityStudyLogUtils() {
            mDate = new Date();
            mDateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss.SSSZ", Locale.US);

            HandlerThread handlerThread = new HandlerThread("UsabilityStudyLogUtils logging task",
                    Process.THREAD_PRIORITY_BACKGROUND);
            handlerThread.start();
            mLoggingHandler = new Handler(handlerThread.getLooper());
        }

        // Initialization-on-demand holder
        private static final class OnDemandInitializationHolder {
            public static final UsabilityStudyLogUtils sInstance = new UsabilityStudyLogUtils();
        }

        public static UsabilityStudyLogUtils getInstance() {
            return OnDemandInitializationHolder.sInstance;
        }

        public void init(final InputMethodService ims) {
            mIms = ims;
            mDirectory = ims.getFilesDir();
        }

        private void createLogFileIfNotExist() {
            if ((mFile == null || !mFile.exists())
                    && (mDirectory != null && mDirectory.exists())) {
                try {
                    mWriter = getPrintWriter(mDirectory, FILENAME, false);
                } catch (final IOException e) {
                    Log.e(USABILITY_TAG, "Can't create log file.");
                }
            }
        }

        public static void writeBackSpace(final int x, final int y) {
            UsabilityStudyLogUtils.getInstance().write("<backspace>\t" + x + "\t" + y);
        }

        public static void writeChar(final char c, final int x, final int y) {
            String inputChar = String.valueOf(c);
            switch (c) {
                case '\n':
                    inputChar = "<enter>";
                    break;
                case '\t':
                    inputChar = "<tab>";
                    break;
                case ' ':
                    inputChar = "<space>";
                    break;
            }
            UsabilityStudyLogUtils.getInstance().write(inputChar + "\t" + x + "\t" + y);
            LatinImeLogger.onPrintAllUsabilityStudyLogs();
        }

        public void write(final String log) {
            mLoggingHandler.post(new Runnable() {
                @Override
                public void run() {
                    createLogFileIfNotExist();
                    final long currentTime = System.currentTimeMillis();
                    mDate.setTime(currentTime);

                    final String printString = String.format(Locale.US, "%s\t%d\t%s\n",
                            mDateFormat.format(mDate), currentTime, log);
                    if (LatinImeLogger.sDBG) {
                        Log.d(USABILITY_TAG, "Write: " + log);
                    }
                    mWriter.print(printString);
                }
            });
        }

        private synchronized String getBufferedLogs() {
            mWriter.flush();
            final StringBuilder sb = new StringBuilder();
            final BufferedReader br = getBufferedReader();
            String line;
            try {
                while ((line = br.readLine()) != null) {
                    sb.append('\n');
                    sb.append(line);
                }
            } catch (final IOException e) {
                Log.e(USABILITY_TAG, "Can't read log file.");
            } finally {
                if (LatinImeLogger.sDBG) {
                    Log.d(USABILITY_TAG, "Got all buffered logs\n" + sb.toString());
                }
                try {
                    br.close();
                } catch (final IOException e) {
                    // ignore.
                }
            }
            return sb.toString();
        }

        public void emailResearcherLogsAll() {
            mLoggingHandler.post(new Runnable() {
                @Override
                public void run() {
                    final Date date = new Date();
                    date.setTime(System.currentTimeMillis());
                    final String currentDateTimeString =
                            new SimpleDateFormat("yyyyMMdd-HHmmssZ", Locale.US).format(date);
                    if (mFile == null) {
                        Log.w(USABILITY_TAG, "No internal log file found.");
                        return;
                    }
                    if (mIms.checkCallingOrSelfPermission(
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        != PackageManager.PERMISSION_GRANTED) {
                        Log.w(USABILITY_TAG, "Doesn't have the permission WRITE_EXTERNAL_STORAGE");
                        return;
                    }
                    mWriter.flush();
                    final String destPath = Environment.getExternalStorageDirectory()
                            + "/research-" + currentDateTimeString + ".log";
                    final File destFile = new File(destPath);
                    try {
                        final FileInputStream srcStream = new FileInputStream(mFile);
                        final FileOutputStream destStream = new FileOutputStream(destFile);
                        final FileChannel src = srcStream.getChannel();
                        final FileChannel dest = destStream.getChannel();
                        src.transferTo(0, src.size(), dest);
                        src.close();
                        srcStream.close();
                        dest.close();
                        destStream.close();
                    } catch (final FileNotFoundException e1) {
                        Log.w(USABILITY_TAG, e1);
                        return;
                    } catch (final IOException e2) {
                        Log.w(USABILITY_TAG, e2);
                        return;
                    }
                    if (destFile == null || !destFile.exists()) {
                        Log.w(USABILITY_TAG, "Dest file doesn't exist.");
                        return;
                    }
                    final Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (LatinImeLogger.sDBG) {
                        Log.d(USABILITY_TAG, "Destination file URI is " + destFile.toURI());
                    }
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + destPath));
                    intent.putExtra(Intent.EXTRA_SUBJECT,
                            "[Research Logs] " + currentDateTimeString);
                    mIms.startActivity(intent);
                }
            });
        }

        public void printAll() {
            mLoggingHandler.post(new Runnable() {
                @Override
                public void run() {
                    mIms.getCurrentInputConnection().commitText(getBufferedLogs(), 0);
                }
            });
        }

        public void clearAll() {
            mLoggingHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mFile != null && mFile.exists()) {
                        if (LatinImeLogger.sDBG) {
                            Log.d(USABILITY_TAG, "Delete log file.");
                        }
                        mFile.delete();
                        mWriter.close();
                    }
                }
            });
        }

        private BufferedReader getBufferedReader() {
            createLogFileIfNotExist();
            try {
                return new BufferedReader(new FileReader(mFile));
            } catch (final FileNotFoundException e) {
                return null;
            }
        }

        private PrintWriter getPrintWriter(final File dir, final String filename,
                final boolean renew) throws IOException {
            mFile = new File(dir, filename);
            if (mFile.exists()) {
                if (renew) {
                    mFile.delete();
                }
            }
            return new PrintWriter(new FileOutputStream(mFile), true /* autoFlush */);
        }
    }

    // TODO: Make this an external class
    public static final class Stats {
        public static void onNonSeparator(final char code, final int x, final int y) {
            UserLogRingCharBuffer.getInstance().push(code, x, y);
            LatinImeLogger.logOnInputChar();
        }

        public static void onSeparator(final int code, final int x, final int y) {
            // Helper method to log a single code point separator
            // TODO: cache this mapping of a code point to a string in a sparse array in StringUtils
            onSeparator(new String(new int[]{code}, 0, 1), x, y);
        }

        public static void onSeparator(final String separator, final int x, final int y) {
            final int length = separator.length();
            for (int i = 0; i < length; i = Character.offsetByCodePoints(separator, i, 1)) {
                int codePoint = Character.codePointAt(separator, i);
                // TODO: accept code points
                UserLogRingCharBuffer.getInstance().push((char)codePoint, x, y);
            }
            LatinImeLogger.logOnInputSeparator();
        }

        public static void onAutoCorrection(final String typedWord, final String correctedWord,
                final String separatorString, final WordComposer wordComposer) {
            final boolean isBatchMode = wordComposer.isBatchMode();
            if (!isBatchMode && TextUtils.isEmpty(typedWord)) {
                return;
            }
            // TODO: this fails when the separator is more than 1 code point long, but
            // the backend can't handle it yet. The only case when this happens is with
            // smileys and other multi-character keys.
            final int codePoint = TextUtils.isEmpty(separatorString) ? Constants.NOT_A_CODE
                    : separatorString.codePointAt(0);
            if (!isBatchMode) {
                LatinImeLogger.logOnAutoCorrectionForTyping(typedWord, correctedWord, codePoint);
            } else {
                if (!TextUtils.isEmpty(correctedWord)) {
                    // We must make sure that InputPointer contains only the relative timestamps,
                    // not actual timestamps.
                    LatinImeLogger.logOnAutoCorrectionForGeometric(
                            "", correctedWord, codePoint, wordComposer.getInputPointers());
                }
            }
        }

        public static void onAutoCorrectionCancellation() {
            LatinImeLogger.logOnAutoCorrectionCancelled();
        }
    }

    public static String getDebugInfo(final SuggestedWords suggestions, final int pos) {
        if (!LatinImeLogger.sDBG) {
            return null;
        }
        final SuggestedWordInfo wordInfo = suggestions.getInfo(pos);
        if (wordInfo == null) {
            return null;
        }
        final String info = wordInfo.getDebugString();
        if (TextUtils.isEmpty(info)) {
            return null;
        }
        return info;
    }

    public static int getAcitivityTitleResId(final Context context,
            final Class<? extends Activity> cls) {
        final ComponentName cn = new ComponentName(context, cls);
        try {
            final ActivityInfo ai = context.getPackageManager().getActivityInfo(cn, 0);
            if (ai != null) {
                return ai.labelRes;
            }
        } catch (final NameNotFoundException e) {
            Log.e(TAG, "Failed to get settings activity title res id.", e);
        }
        return 0;
    }

    /**
     * A utility method to get the application's PackageInfo.versionName
     * @return the application's PackageInfo.versionName
     */
    public static String getVersionName(final Context context) {
        try {
            if (context == null) {
                return "";
            }
            final String packageName = context.getPackageName();
            final PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName;
        } catch (final NameNotFoundException e) {
            Log.e(TAG, "Could not find version info.", e);
        }
        return "";
    }
}