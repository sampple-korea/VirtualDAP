VIRTUALDAP_PLATFORM_RUNTIME_PATH := $(call my-dir)

PRODUCT_PACKAGES += \
    VirtualDapPlatformRuntime \
    privapp-permissions-virtualdap-runtime

# A graphical custom VM requires the AVF APEX and a crosvm-compatible U-Boot payload supplied by
# the device product. Do not copy an unsigned bootloader from this repository.
PRODUCT_PACKAGES += com.android.virt

# Reuse AOSP's deliberately narrow graphical custom-VM launcher domain. This domain already grants
# the AVF/display Binder and vsock operations required by the reference provider.
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(VIRTUALDAP_PLATFORM_RUNTIME_PATH)/sepolicy/private
