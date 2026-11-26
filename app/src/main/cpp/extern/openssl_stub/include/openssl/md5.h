#pragma once

#include <boost/uuid/detail/md5.hpp>
#include <cstddef>

// Orca uses the OpenSSL MD5 API for legacy 3MF file checksums.
using MD5_CTX = boost::uuids::detail::md5;

inline int MD5_Init(MD5_CTX* context)
{
    *context = MD5_CTX{};
    return 1;
}

inline int MD5_Update(MD5_CTX* context, const void* data, std::size_t size)
{
    if (size != 0) {
        context->process_bytes(data, size);
    }
    return 1;
}

inline int MD5_Final(unsigned char* output, MD5_CTX* context)
{
    MD5_CTX::digest_type digest;
    context->get_digest(digest);
    const auto* bytes = reinterpret_cast<const unsigned char*>(digest);
    for (std::size_t i = 0; i < 16; ++i) {
#if BOOST_ENDIAN_LITTLE_BYTE && !defined(BOOST_UUID_COMPAT_PRE_1_71_MD5)
        // Boost 1.84 stores each word in reverse order for UUID generation.
        output[i] = bytes[i ^ 3];
#else
        output[i] = bytes[i];
#endif
    }
    return 1;
}
