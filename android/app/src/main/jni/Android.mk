TOP_PATH := $(call my-dir)
HEV_PATH := $(TOP_PATH)/hev-socks5-tunnel
YAML_PATH := $(HEV_PATH)/third-part/yaml
LWIP_PATH := $(HEV_PATH)/third-part/lwip
HEV_TASK_SYSTEM_PATH := $(HEV_PATH)/third-part/hev-task-system

rwildcard=$(foreach d,$(wildcard $1*), \
          $(call rwildcard,$d/,$2) \
          $(filter $(subst *,%,$2),$d))

HEV_TASK_SYSTEM_SRC := $(HEV_TASK_SYSTEM_PATH)/src
HEV_TASK_SYSTEM_HEADER_DIRS := $(sort $(dir $(call rwildcard,$(HEV_TASK_SYSTEM_SRC)/,*.h)))

LOCAL_PATH := $(YAML_PATH)
SRCDIR := $(LOCAL_PATH)/src
include $(CLEAR_VARS)
include $(LOCAL_PATH)/build.mk
include $(LOCAL_PATH)/configs.mk
LOCAL_MODULE    := libyaml
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
LOCAL_C_INCLUDES := $(SRCDIR) $(LOCAL_PATH)/include
LOCAL_CFLAGS += $(CONFIG_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
include $(BUILD_STATIC_LIBRARY)

LOCAL_PATH := $(LWIP_PATH)
SRCDIR := $(LOCAL_PATH)/src
include $(CLEAR_VARS)
include $(LOCAL_PATH)/build.mk
LOCAL_MODULE    := liblwip
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/src/include $(LOCAL_PATH)/src/ports/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
include $(BUILD_STATIC_LIBRARY)

LOCAL_PATH := $(HEV_TASK_SYSTEM_PATH)
SRCDIR := $(LOCAL_PATH)/src
include $(CLEAR_VARS)
include $(LOCAL_PATH)/build.mk
include $(LOCAL_PATH)/configs.mk
LOCAL_MODULE    := libhev-task-system
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
LOCAL_C_INCLUDES := \
	$(TOP_PATH)/windows-headers \
	$(HEV_TASK_SYSTEM_HEADER_DIRS) \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/include
LOCAL_CFLAGS += -fvisibility=hidden $(CONFIG_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
include $(BUILD_STATIC_LIBRARY)

LOCAL_PATH := $(HEV_PATH)
SRCDIR := $(LOCAL_PATH)/src
include $(CLEAR_VARS)
include $(LOCAL_PATH)/build.mk
LOCAL_MODULE    := hev-socks5-tunnel
LOCAL_SRC_FILES := \
	$(filter-out src/hev-jni.c src/hev-socks5-session.c,$(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))) \
	../android-overrides/hev-jni-android.c \
	../android-overrides/hev-socks5-session-android.c
LOCAL_C_INCLUDES := \
	$(TOP_PATH)/windows-headers \
	$(HEV_TASK_SYSTEM_HEADER_DIRS) \
	$(LOCAL_PATH)/third-part/yaml/src \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/src/misc \
	$(LOCAL_PATH)/src/core/src \
	$(LOCAL_PATH)/src/core/include \
	$(LOCAL_PATH)/third-part/yaml/include \
	$(LOCAL_PATH)/third-part/lwip/src/include \
	$(LOCAL_PATH)/third-part/lwip/src/ports/include \
	$(LOCAL_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY
LOCAL_CFLAGS += $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDLIBS += -llog
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
