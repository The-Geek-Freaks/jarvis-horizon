LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := jarvis-wake-jni
LOCAL_SRC_FILES := jarvis_wake_jni.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_CPP_FEATURES := exceptions
LOCAL_CPPFLAGS := -std=c++17 -fvisibility=hidden
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
