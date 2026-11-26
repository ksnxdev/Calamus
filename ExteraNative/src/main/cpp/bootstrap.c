#include <jni.h>
#include <exterahook.h>
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) { return ExteraHookJniOnLoad(vm); }
JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) { ExteraHookJniOnUnload(vm); }
