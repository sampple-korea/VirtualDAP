# Android virtualization source and maintenance audit

Checked 2026-09-13. GitHub API commit histories, actual build settings and source files were examined,
not just repository `pushed_at` dates. Dates below are observations of specific public paths; they
do not certify runtime compatibility. Source checkouts are investigation artifacts and are not
silently incorporated into VirtualDAP.

The required outcome remains a normal installed APK, no root or OEM/platform signing, with a
customizable Android environment and a controlled music path. An app container reuses the host
Android framework; it is not a separately booted Android 13 OS. That distinction cannot be hidden
by labeling either one a “VM.”

| Project | Actual maintenance/source findings | Fit and remaining work |
| --- | --- | --- |
| [Twoyi original](https://github.com/twoyi/twoyi) | Archived April 2023. Public app supports an Android 8.1 guest; README says the complete ROM cannot be built from the published source. | Useful system-container and renderer reference, not a ready modern guest. |
| [Twoyi topminipie fork](https://github.com/topminipie/twoyi/tree/b121f061ccd27a314151f2b47e4fc7e2b260ef0b) | Last commit September 2025, now archived. Actual build targets API 27, ARM64 only; Rust launcher still fixes `/data/data/io.twoyi/rootfs`; CI downloads an existing ROM. | Recent repository activity did not upgrade its guest or make it a package-independent Android 13 engine. |
| [Twoyi althafvly fork](https://github.com/althafvly/twoyi/tree/ea22ba9043049595b53353bdae062585808d5147) | Last commit November 2023, mainly ADB/dependency/native-library updates. | No evidence of a current, fully buildable guest ROM. |
| [VirtualApp](https://github.com/asLody/VirtualApp) | README updated August 2026; public `VirtualApp/lib` path last changed June 2018. Current Android support is advertised for its commercial version. | Established app virtualization architecture. Public legacy core and advertised commercial engine are different deliverables. |
| [NewBlackbox](https://github.com/ALEX5402/NewBlackbox/tree/89b59836c66f173756a4ae258cf379a957649820) | Real commits July 2026. Source uses compile SDK 35, target SDK 28 and ARM/ARM64 ABI filters. `BuildCompat.isU()` tests API 33; `isTiramisu()` tests API 32, both one release early. | A current app-container candidate that still needs concrete API/ABI compatibility work. It does not boot an independent Android OS. |
| [Blacks-BlackBox](https://github.com/Black00Z/Blacks-BlackBox/tree/40282a7bf4500948cfd598fc67e6e63114b26dd9) | Engine changes March–May 2026, including split APK handling and newer service proxies. Maintainer reports Android 16 app launches; the checkout still targets API 28, ARM/ARM64, and retains the same version-test issues. | Relevant compatibility patches. README explicitly does not claim universal app compatibility. Its cited `tools/smoke_install_launch.sh` is not present in the inspected tree. |
| [Android-Virtual-Inject](https://github.com/reveny/Android-Virtual-Inject) | Latest published release January 2025, based on NewBlackbox; described Android 14 support. | Native instrumentation reference, not a full Android system engine. |
| [VineOS](https://github.com/Hexadecinull/VineOS/tree/f72a8ccdc69ea474cc60d4855c836121adbcf273) | Repository active in 2026. Examined engine calls loop-device mount, namespace creation, bind mounts and `pivot_root`; no-root alternatives are roadmap work. | Current code does not meet ordinary-app privileges merely because the README says “no root on most paths.” |
| [ShadowCore](https://github.com/ReturnKartikey/ShadowCore) | June 2026 commit removes BlackBox stub components that caused startup crashes. Public project is a small integration/UI layer. | Not yet evidence of an independently working system runtime. |
| [VPhoneOS](https://github.com/VPhoneOS/VPhoneOS) | Public repository has a README, no engine source or reusable SDK. | Product behavior is a useful reference, but no implementation can be adopted from this repository. |
| [VMOS](https://page.vmos.com/vmos/) | Maintained proprietary product; official consumer descriptions are not a source-code distribution. | Requires an actual redistribution/integration agreement before it can serve as an embedded engine. |
| [Limbo](https://github.com/limboemu/limbo/tree/887c6a68cd6b414377d7f8071bc12bbc16e59809) | Last public commit March 2022; developer instructions center on QEMU 5.1/2.9. | Useful historical Android porting reference, too old to adopt unchanged. |
| [Vectras VM](https://github.com/xoureldeen/Vectras-VM-Android) | Actual app source changed July 2026; README changed September 2026. Maintained QEMU-based Android VM product. Public README distinguishes its older proot bootstrap from a newer native Play build. | Current full-system reference worth examining. Performance and source/build parity need verification; maintenance alone does not prove music suitability. |
| [MultiApp](https://github.com/WaxMoon/MultiApp) | README updated June 2026 but explicitly says the public repository has not been maintained since 2023. It exposes an `opensdk` submodule and advertises a separate current commercial product. | Another case where current product advertising must not be mistaken for a current public engine. |
| [AVF](https://source.android.com/docs/core/virtualization) | Current AOSP code, but custom graphical VMs use platform permissions and device capabilities. | Cannot be the mandatory consumer backend under the user's no-root/no-device-restriction requirement. |

Twoyi organization repositories illustrate why `pushed_at` is insufficient: its ART repository
reported an August 2026 push, but its current branch's last commit was May 2023; `system_vold`
reported an August 2026 push while the current branch's last commit was October 2022.

The supplied UAPP 7.1.2.3 APK declares minimum API 32. A ready-made Android 8.1 guest therefore cannot
run that application even if its host wrapper recently changed.

## Selection status

The unmodified Blacks-BlackBox checkout at `40282a7` successfully built `:Bcore:assembleDebug`
locally with its Gradle 8.13 / NDK 29.0.13846066 configuration, including both ARM ABIs. This
establishes that its public core is buildable; it does not establish music-app playback or separate
guest-OS support. The build produced many duplicate-permission warnings. The audio-service proxy
also contains forced microphone-unmute behavior that must not be adopted for VirtualDAP.

The user has explicitly accepted an app container if it delivers the target music/audio behavior.
The selected integration direction is the inspected Blacks-BlackBox core, with privacy adaptations,
current build tooling and source-built native hooks. Runtime/app/audio validation is still required;
selection does not mean universal app compatibility has been demonstrated. Twoyi remains an older
system-container reference; Vectras and upstream QEMU/Termux remain system-emulation references.
They are not the selected mandatory backend.

Keep the tested PCM bridge, output negotiation and USB/DSD components independent of this decision.
Do not declare a working rootless guest on the strength of a placeholder service, a successful APK
build, a recent README, or a Linux test running as root.
