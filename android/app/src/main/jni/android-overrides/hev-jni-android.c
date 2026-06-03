/*
 * Android JNI entrypoints for hev-socks5-tunnel.
 *
 * This local override keeps third-party source untouched while adding
 * VpnService.protect(fd) support for upstream Clash sockets.
 */

#ifdef ANDROID

#include <jni.h>
#include <android/log.h>
#include <pthread.h>

#include <stdlib.h>
#include <string.h>

#include "hev-main.h"

#ifndef PKGNAME
#define PKGNAME hev/htproxy
#endif
#ifndef CLSNAME
#define CLSNAME TProxyService
#endif

#define STR(s) STR_ARG (s)
#define STR_ARG(c) #c
#define N_ELEMENTS(arr) (sizeof (arr) / sizeof ((arr)[0]))
#define LOG_TAG "CatchReportNative"

typedef struct _ThreadData ThreadData;

struct _ThreadData
{
    char *path;
    int fd;
    jobject vpn_service;
};

static int is_working;
static JavaVM *java_vm;
static pthread_t work_thread;
static pthread_mutex_t mutex;
static pthread_key_t current_jni_env;
static jobject protect_vpn_service;

static void native_start_service (JNIEnv *env, jobject thiz, jobject vpn_service,
                                  jstring config_path, jint fd);
static void native_stop_service (JNIEnv *env, jobject thiz);
static jlongArray native_get_stats (JNIEnv *env, jobject thiz);

static JNINativeMethod native_methods[] = {
    { "TProxyStartService", "(Landroid/net/VpnService;Ljava/lang/String;I)V",
      (void *)native_start_service },
    { "TProxyStopService", "()V", (void *)native_stop_service },
    { "TProxyGetStats", "()[J", (void *)native_get_stats },
};

static void
detach_current_thread (void *env)
{
    (void)env;
    (*java_vm)->DetachCurrentThread (java_vm);
}

static JNIEnv *
get_jni_env (void)
{
    JNIEnv *env = pthread_getspecific (current_jni_env);

    if (env)
        return env;

    if (JNI_OK != (*java_vm)->GetEnv (java_vm, (void **)&env, JNI_VERSION_1_4)) {
        if (JNI_OK != (*java_vm)->AttachCurrentThread (java_vm, &env, NULL))
            return NULL;
        pthread_setspecific (current_jni_env, env);
    }

    return env;
}

jint
JNI_OnLoad (JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;
    jclass klass;

    (void)reserved;
    java_vm = vm;
    if (JNI_OK != (*vm)->GetEnv (vm, (void **)&env, JNI_VERSION_1_4))
        return 0;

    klass = (*env)->FindClass (env, STR (PKGNAME) "/" STR (CLSNAME));
    (*env)->RegisterNatives (env, klass, native_methods, N_ELEMENTS (native_methods));
    (*env)->DeleteLocalRef (env, klass);

    pthread_key_create (&current_jni_env, detach_current_thread);
    pthread_mutex_init (&mutex, NULL);

    return JNI_VERSION_1_4;
}

int
hev_android_protect_socket (int fd)
{
    JNIEnv *env;
    jclass klass;
    jmethodID protect_method;
    jboolean ok;

    if (!protect_vpn_service)
    {
        __android_log_print (ANDROID_LOG_WARN, LOG_TAG, "protect failed: vpn service is null");
        return -1;
    }

    env = get_jni_env ();
    if (!env) {
        __android_log_print (ANDROID_LOG_WARN, LOG_TAG, "protect failed: no JNIEnv");
        return -1;
    }

    klass = (*env)->GetObjectClass (env, protect_vpn_service);
    if (!klass) {
        __android_log_print (ANDROID_LOG_WARN, LOG_TAG, "protect failed: no class");
        return -1;
    }

    protect_method = (*env)->GetMethodID (env, klass, "protect", "(I)Z");
    (*env)->DeleteLocalRef (env, klass);
    if (!protect_method) {
        __android_log_print (ANDROID_LOG_WARN, LOG_TAG, "protect failed: method lookup");
        return -1;
    }

    ok = (*env)->CallBooleanMethod (env, protect_vpn_service, protect_method, fd);
    if ((*env)->ExceptionCheck (env)) {
        (*env)->ExceptionClear (env);
        __android_log_print (ANDROID_LOG_WARN, LOG_TAG, "protect failed: Java exception");
        return -1;
    }

    if (!ok)
        __android_log_print (ANDROID_LOG_WARN, LOG_TAG, "protect failed: VpnService returned false");

    return ok ? 0 : -1;
}

static void *
thread_handler (void *data)
{
    ThreadData *tdata = data;

    protect_vpn_service = tdata->vpn_service;
    hev_socks5_tunnel_main (tdata->path, tdata->fd);
    protect_vpn_service = NULL;

    if (tdata->vpn_service) {
        JNIEnv *env = get_jni_env ();
        if (env)
            (*env)->DeleteGlobalRef (env, tdata->vpn_service);
    }
    free (tdata->path);
    free (tdata);

    return NULL;
}

static void
native_start_service (JNIEnv *env, jobject thiz, jobject vpn_service,
                      jstring config_path, jint fd)
{
    const jbyte *bytes;
    ThreadData *tdata;
    int res;

    (void)thiz;
    pthread_mutex_lock (&mutex);

    if (is_working)
        goto exit;

    tdata = malloc (sizeof (ThreadData));
    if (!tdata)
        goto exit;

    tdata->fd = fd;
    tdata->vpn_service = vpn_service ? (*env)->NewGlobalRef (env, vpn_service) : NULL;

    bytes = (const jbyte *)(*env)->GetStringUTFChars (env, config_path, NULL);
    tdata->path = strdup ((const char *)bytes);
    (*env)->ReleaseStringUTFChars (env, config_path, (const char *)bytes);

    res = pthread_create (&work_thread, NULL, thread_handler, tdata);
    if (res != 0) {
        if (tdata->vpn_service)
            (*env)->DeleteGlobalRef (env, tdata->vpn_service);
        free (tdata->path);
        free (tdata);
        goto exit;
    }

    is_working = 1;
exit:
    pthread_mutex_unlock (&mutex);
}

static void
native_stop_service (JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    pthread_mutex_lock (&mutex);

    if (!is_working)
        goto exit;

    hev_socks5_tunnel_quit ();
    pthread_join (work_thread, NULL);

    is_working = 0;
exit:
    pthread_mutex_unlock (&mutex);
}

static jlongArray
native_get_stats (JNIEnv *env, jobject thiz)
{
    size_t tx_packets, rx_packets, tx_bytes, rx_bytes;
    jlongArray res;
    jlong array[4];

    (void)thiz;
    hev_socks5_tunnel_stats (&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    array[0] = tx_packets;
    array[1] = tx_bytes;
    array[2] = rx_packets;
    array[3] = rx_bytes;

    res = (*env)->NewLongArray (env, 4);
    (*env)->SetLongArrayRegion (env, res, 0, 4, array);

    return res;
}

#endif /* ANDROID */
