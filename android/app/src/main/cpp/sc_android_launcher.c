#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "SeveredChainsJVM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef jint JLI_Launch_func(
  int argc,
  char **argv,
  int jargc,
  const char **jargv,
  int appclassc,
  const char **appclassv,
  const char *fullversion,
  const char *dotversion,
  const char *pname,
  const char *lname,
  jboolean javaargs,
  jboolean cpwildcard,
  jboolean javaw,
  jint ergo
);

static char *copy_jstring(JNIEnv *env, jstring value) {
  if(value == NULL) {
    return NULL;
  }

  const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
  if(utf == NULL) {
    return NULL;
  }

  char *copy = strdup(utf);
  (*env)->ReleaseStringUTFChars(env, value, utf);
  return copy;
}

static void preload_if_present(const char *path) {
  if(access(path, R_OK) != 0) {
    return;
  }

  void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
  if(handle == NULL) {
    LOGI("Optional preload failed for %s: %s", path, dlerror());
  }
}

static void preload_runtime(const char *runtime) {
  const char *libs[] = {
    "libjimage.so",
    "libjava.so",
    "libverify.so",
    "libzip.so",
    "libnet.so",
    "libnio.so",
    "libmanagement.so",
    "libmanagement_ext.so",
    "libinstrument.so",
    "libjsig.so",
    NULL
  };

  char path[PATH_MAX];

  for(int i = 0; libs[i] != NULL; i++) {
    snprintf(path, sizeof(path), "%s/lib/%s", runtime, libs[i]);
    preload_if_present(path);
  }

  snprintf(path, sizeof(path), "%s/lib/server/libjvm.so", runtime);
  preload_if_present(path);
}

JNIEXPORT jint JNICALL
Java_org_legendofdragoon_severedchains_android_NativeLauncher_launch(
  JNIEnv *env,
  jclass clazz,
  jstring runtime_home,
  jstring game_directory,
  jstring cache_directory,
  jstring native_library_directory,
  jstring class_path
) {
  (void)clazz;

  char *runtime = copy_jstring(env, runtime_home);
  char *game = copy_jstring(env, game_directory);
  char *cache = copy_jstring(env, cache_directory);
  char *native_dir = copy_jstring(env, native_library_directory);
  char *classpath = copy_jstring(env, class_path);

  if(runtime == NULL || game == NULL || cache == NULL || native_dir == NULL || classpath == NULL) {
    LOGE("Failed to copy JVM launch arguments");
    goto failure;
  }

  if(chdir(game) != 0) {
    LOGE("Unable to chdir to %s", game);
    goto failure;
  }

  char runtime_lib[PATH_MAX];
  char runtime_server[PATH_MAX];
  char lib_path[PATH_MAX * 2];

  snprintf(runtime_lib, sizeof(runtime_lib), "%s/lib", runtime);
  snprintf(runtime_server, sizeof(runtime_server), "%s/lib/server", runtime);
  snprintf(lib_path, sizeof(lib_path), "%s:%s:%s", native_dir, runtime_lib, runtime_server);

  setenv("JAVA_HOME", runtime, 1);
  setenv("HOME", game, 1);
  setenv("TMPDIR", cache, 1);
  setenv("LD_LIBRARY_PATH", lib_path, 1);

  preload_runtime(runtime);

  char jli_path[PATH_MAX];
  snprintf(jli_path, sizeof(jli_path), "%s/lib/libjli.so", runtime);

  void *jli = dlopen(jli_path, RTLD_NOW | RTLD_GLOBAL);
  if(jli == NULL) {
    LOGE("Unable to load %s: %s", jli_path, dlerror());
    goto failure;
  }

  JLI_Launch_func *launch = (JLI_Launch_func *)dlsym(jli, "JLI_Launch");
  if(launch == NULL) {
    LOGE("JLI_Launch missing from %s: %s", jli_path, dlerror());
    goto failure;
  }

  char java_home[PATH_MAX + 32];
  char tmp_dir[PATH_MAX + 32];
  char user_home[PATH_MAX + 32];
  char java_lib_path[PATH_MAX * 2 + 64];
  char lwjgl_lib_path[PATH_MAX + 64];

  snprintf(java_home, sizeof(java_home), "-Djava.home=%s", runtime);
  snprintf(tmp_dir, sizeof(tmp_dir), "-Djava.io.tmpdir=%s", cache);
  snprintf(user_home, sizeof(user_home), "-Duser.home=%s", game);
  snprintf(java_lib_path, sizeof(java_lib_path), "-Djava.library.path=%s", lib_path);
  snprintf(lwjgl_lib_path, sizeof(lwjgl_lib_path), "-Dorg.lwjgl.librarypath=%s", native_dir);

  char *argv[] = {
    "java",
    "-Xms128m",
    "-Xmx1024m",
    "-Dseveredchains.android=true",
    "-Dorg.lwjgl.system.allocator=system",
    "-Dorg.lwjgl.opengles.libname=libGLESv2.so",
    "-Dorg.lwjgl.openal.libname=libopenal.so",
    "-Djoml.fastmath",
    "-Djoml.sinLookup",
    "-Djoml.useMathFma",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    java_home,
    tmp_dir,
    user_home,
    java_lib_path,
    lwjgl_lib_path,
    "-cp",
    classpath,
    "legend.game.Main"
  };

  const int argc = (int)(sizeof(argv) / sizeof(argv[0]));

  LOGI("Starting embedded Java 25 (%d JVM args)", argc);
  const jint result = launch(
    argc,
    argv,
    0,
    NULL,
    0,
    NULL,
    "25-internal",
    "25",
    "java",
    "java",
    JNI_FALSE,
    JNI_TRUE,
    JNI_FALSE,
    0
  );

  LOGI("JLI_Launch returned %d", result);

  free(runtime);
  free(game);
  free(cache);
  free(native_dir);
  free(classpath);
  return result;

failure:
  free(runtime);
  free(game);
  free(cache);
  free(native_dir);
  free(classpath);
  return -1;
}
