package ksnxdev.calamus.plugins;

final class NativePython {
    static { System.loadLibrary("calamus_python"); }
    static native int sealedData(byte[] bytes);
    static native String initialize(Object bridge, String stdlib, byte[] archive, String source, String config);
    static native String dispatch(String operation, String argument);
}
