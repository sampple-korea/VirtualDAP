#include <dobby.h>
#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

/** Android/ELF code patching with runtime page size and checked protection changes. */
extern "C" int DobbyCodePatch(void* address, uint8_t* buffer, uint32_t buffer_size) {
    if (buffer_size == 0) return 0;
    if (address == nullptr || buffer == nullptr) return -1;
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return -1;
    const uintptr_t start = reinterpret_cast<uintptr_t>(address);
    const uintptr_t mask = static_cast<uintptr_t>(page_size) - 1;
    if ((static_cast<uintptr_t>(page_size) & mask) != 0 ||
        start > UINTPTR_MAX - buffer_size ||
        start + buffer_size > UINTPTR_MAX - mask) return -1;
    const uintptr_t page_start = start & ~mask;
    const uintptr_t page_end = (start + buffer_size + mask) & ~mask;
    const size_t span = page_end - page_start;
    if (mprotect(reinterpret_cast<void*>(page_start), span, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return -1;
    }
    std::memcpy(address, buffer, buffer_size);
    __builtin___clear_cache(reinterpret_cast<char*>(address),
                            reinterpret_cast<char*>(address) + buffer_size);
    return mprotect(reinterpret_cast<void*>(page_start), span, PROT_READ | PROT_EXEC) == 0 ? 0 : -1;
}
