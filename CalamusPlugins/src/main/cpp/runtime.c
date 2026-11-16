#define _GNU_SOURCE
#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stddef.h>
#include <string.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/audit.h>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>

static JavaVM *vm;
static jobject requester;
static jmethodID request_method;
static PyObject *dispatcher;
static int initialized;
static PyThreadState *control_state;

static int isolated(void) {
    int app = getuid() % 100000;
    return app >= 90000 && app <= 99999;
}

static PyObject *from_java(JNIEnv *env, jstring input) {
    const jchar *chars = (*env)->GetStringChars(env, input, NULL);
    int order = -1;
    PyObject *value = PyUnicode_DecodeUTF16((const char *)chars, (*env)->GetStringLength(env, input) * 2, "strict", &order);
    (*env)->ReleaseStringChars(env, input, chars);
    return value;
}

static jstring to_java(JNIEnv *env, PyObject *text) {
    PyObject *encoded = PyUnicode_AsEncodedString(text, "utf-16-le", "strict");
    if (!encoded) return NULL;
    jstring result = (*env)->NewString(env, (const jchar *)PyBytes_AsString(encoded), PyBytes_Size(encoded) / 2);
    Py_DECREF(encoded);
    return result;
}

static jstring error_string(JNIEnv *env) {
    PyObject *type = NULL, *value = NULL, *trace = NULL;
    PyErr_Fetch(&type, &value, &trace);
    PyObject *text = value ? PyObject_Str(value) : PyUnicode_FromString("Python operation failed");
    PyObject *bounded = text ? PyUnicode_Substring(text, 0, 1500) : NULL;
    jstring result = bounded ? to_java(env, bounded) : (*env)->NewStringUTF(env, "Python operation failed");
    Py_XDECREF(bounded); Py_XDECREF(text); Py_XDECREF(type); Py_XDECREF(value); Py_XDECREF(trace);
    PyErr_Clear();
    return result;
}

static PyObject *broker(PyObject *self, PyObject *args) {
    PyObject *packet;
    if (!PyArg_ParseTuple(args, "U", &packet)) return NULL;
    if (PyUnicode_GetLength(packet) > 65536) return PyErr_Format(PyExc_ValueError, "Request exceeds size limit");
    JNIEnv *env = NULL; int attached = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return PyErr_Format(PyExc_RuntimeError, "Bridge unavailable");
        attached = 1;
    }
    jstring input = to_java(env, packet);
    if (!input) return NULL;
    jstring response;
    Py_BEGIN_ALLOW_THREADS
    response = (jstring)(*env)->CallObjectMethod(env, requester, request_method, input);
    Py_END_ALLOW_THREADS
    (*env)->DeleteLocalRef(env, input);
    PyObject *result = NULL;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env); PyErr_SetString(PyExc_RuntimeError, "Bridge request failed");
    } else if (response) {
        result = from_java(env, response); (*env)->DeleteLocalRef(env, response);
    } else PyErr_SetString(PyExc_RuntimeError, "Bridge response missing");
    if (attached) (*vm)->DetachCurrentThread(vm);
    return result;
}

static PyMethodDef methods[] = {{"request", broker, METH_VARARGS, NULL}, {NULL, NULL, 0, NULL}};
static struct PyModuleDef module = {PyModuleDef_HEAD_INIT, "_calamus", NULL, -1, methods};
static PyObject *make_module(void) { return PyModule_Create(&module); }

#define DENY(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (n), 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)
#define DENY_EXEC(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (n), 0, 3), BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[2])), BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, 4, 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM), BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr))

static int lockdown(void) {
#if defined(__aarch64__)
    const unsigned arch = AUDIT_ARCH_AARCH64;
#elif defined(__arm__)
    const unsigned arch = AUDIT_ARCH_ARM;
#else
    const unsigned arch = AUDIT_ARCH_X86_64;
#endif
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, arch, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
#if defined(__x86_64__)
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
#endif
#ifdef __NR_fork
        DENY(__NR_fork),
#endif
#ifdef __NR_vfork
        DENY(__NR_vfork),
#endif
        DENY(__NR_execve),
#ifdef __NR_execveat
        DENY(__NR_execveat),
#endif
#ifdef __NR_clone3
        DENY(__NR_clone3),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, CLONE_THREAD, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        DENY(__NR_socket),
#ifdef __NR_socketcall
        DENY(__NR_socketcall),
#endif
        DENY(__NR_ptrace),
#ifdef __NR_process_vm_readv
        DENY(__NR_process_vm_readv),
        DENY(__NR_process_vm_writev),
#endif
#ifdef __NR_memfd_create
        DENY(__NR_memfd_create),
#endif
#if defined(__arm__) && defined(__NR_mmap)
        DENY(__NR_mmap),
#elif defined(__NR_mmap)
        DENY_EXEC(__NR_mmap),
#endif
#ifdef __NR_mmap2
        DENY_EXEC(__NR_mmap2),
#endif
        DENY_EXEC(__NR_mprotect),
#ifdef __NR_pkey_mprotect
        DENY_EXEC(__NR_pkey_mprotect),
#endif
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program = {(unsigned short)(sizeof(filter) / sizeof(filter[0])), filter};
    struct rlimit core = {0, 0}, descriptors = {128, 128}, tasks = {32, 32};
    if (setrlimit(RLIMIT_CORE, &core) || setrlimit(RLIMIT_NOFILE, &descriptors) || setrlimit(RLIMIT_NPROC, &tasks)) return -1;
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) return -1;
    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program);
}

JNIEXPORT jint JNICALL Java_ksnxdev_calamus_plugins_NativePython_sealedData(JNIEnv *env, jclass cls, jbyteArray data) {
    int fd = (int)syscall(__NR_memfd_create, "calamus-readonly", 3);
    if (fd < 0) return -1;
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    int offset = 0;
    while (offset < size) {
        ssize_t amount = write(fd, bytes + offset, size - offset);
        if (amount <= 0) break;
        offset += (int)amount;
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (offset != size || fcntl(fd, 1033, 1 | 2 | 4 | 8) < 0 || lseek(fd, 0, SEEK_SET) < 0) { close(fd); return -1; }
    return fd;
}

JNIEXPORT jstring JNICALL Java_ksnxdev_calamus_plugins_NativePython_initialize(JNIEnv *env, jclass cls, jobject bridge, jstring stdlib, jbyteArray archive, jstring code, jstring config) {
    if (!isolated() || initialized) return (*env)->NewStringUTF(env, "Python requires a fresh OS-isolated process");
    (*env)->GetJavaVM(env, &vm);
    requester = (*env)->NewGlobalRef(env, bridge);
    jclass bridge_class = (*env)->GetObjectClass(env, bridge);
    request_method = (*env)->GetMethodID(env, bridge_class, "request", "(Ljava/lang/String;)Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, bridge_class);
    if (!request_method || PyImport_AppendInittab("_calamus", make_module) == -1) return (*env)->NewStringUTF(env, "Python bridge initialization failed");
    const char *path = (*env)->GetStringUTFChars(env, stdlib, NULL);
    PyConfig options; PyConfig_InitIsolatedConfig(&options);
    options.site_import = 0; options.write_bytecode = 0; options.configure_c_stdio = 0;
    options.module_search_paths_set = 1;
    PyConfig_SetString(&options, &options.program_name, L"Calamus");
    PyConfig_SetString(&options, &options.home, L"/calamus");
    wchar_t *wide = Py_DecodeLocale(path, NULL);
    PyWideStringList_Append(&options.module_search_paths, wide); PyMem_RawFree(wide);
    (*env)->ReleaseStringUTFChars(env, stdlib, path);
    PyStatus status = Py_InitializeFromConfig(&options); PyConfig_Clear(&options);
    if (PyStatus_Exception(status)) { if (PyErr_Occurred()) return error_string(env); return (*env)->NewStringUTF(env, status.err_msg ? status.err_msg : "Python startup failed"); }
    PyObject *globals = PyModule_GetDict(PyImport_AddModule("__main__"));
    PyObject *settings = from_java(env, config);
    PyDict_SetItemString(globals, "_config_json", settings); Py_DECREF(settings);
    jbyte *data = (*env)->GetByteArrayElements(env, archive, NULL);
    PyObject *package = PyBytes_FromStringAndSize((const char *)data, (*env)->GetArrayLength(env, archive));
    (*env)->ReleaseByteArrayElements(env, archive, data, JNI_ABORT);
    PyDict_SetItemString(globals, "_archive_bytes", package); Py_DECREF(package);
    const char *source = (*env)->GetStringUTFChars(env, code, NULL);
    PyObject *result = PyRun_String(source, Py_file_input, globals, globals);
    (*env)->ReleaseStringUTFChars(env, code, source);
    if (!result) return error_string(env);
    Py_DECREF(result);
    dispatcher = PyDict_GetItemString(globals, "dispatch"); Py_XINCREF(dispatcher);
    if (!dispatcher || lockdown()) return (*env)->NewStringUTF(env, "OS syscall restrictions could not be installed");
    initialized = 1;
    control_state = PyEval_SaveThread();
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT jstring JNICALL Java_ksnxdev_calamus_plugins_NativePython_dispatch(JNIEnv *env, jclass cls, jstring op, jstring argument) {
    if (!isolated() || !initialized) return (*env)->NewStringUTF(env, "Isolated Python is unavailable");
    PyEval_RestoreThread(control_state);
    PyObject *operation = from_java(env, op), *json = from_java(env, argument);
    if (!operation || !json) {
        Py_XDECREF(operation); Py_XDECREF(json);
        jstring error = error_string(env); control_state = PyEval_SaveThread(); return error;
    }
    PyObject *result = PyObject_CallFunctionObjArgs(dispatcher, operation, json, NULL);
    Py_DECREF(operation); Py_DECREF(json);
    jstring response;
    if (!result) response = error_string(env);
    else { Py_DECREF(result); response = (*env)->NewStringUTF(env, ""); }
    control_state = PyEval_SaveThread();
    return response;
}
