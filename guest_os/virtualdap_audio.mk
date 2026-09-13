# Add this product fragment to the guest Android product makefile.
VIRTUALDAP_GUEST_PATH := $(call my-dir)

PRODUCT_PACKAGES += \
    audio.primary.virtualdap \
    android.hardware.audio.service \
    android.hardware.audio@7.1-impl \
    android.hardware.audio.effect@7.0-impl

PRODUCT_COPY_FILES += \
    $(VIRTUALDAP_GUEST_PATH)/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml

PRODUCT_VENDOR_PROPERTIES += \
    ro.hardware.audio.primary=virtualdap \
    ro.vendor.virtualdap.socket_name=virtualdap_audio_v1
