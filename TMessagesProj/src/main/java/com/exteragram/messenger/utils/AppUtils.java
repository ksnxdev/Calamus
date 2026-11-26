package com.exteragram.messenger.utils;

import org.telegram.messenger.FileLog;
public final class AppUtils {
    public static void log(String value) { FileLog.d(value); }
    public static void log(Throwable value) { FileLog.e(value); }
    public static void log(String value, Throwable error) { FileLog.e(value, error); }
}
