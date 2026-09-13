#include <windows.h>
#include <bcrypt.h>
#include <stdexcept>
inline void SHA1(char* output,const char* input,size_t length) {
 BCRYPT_ALG_HANDLE alg;
 if(BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA1_ALGORITHM,nullptr,0)<0) throw std::runtime_error("SHA1 provider");
 if(BCryptHash(alg,nullptr,0,(PUCHAR)input,(ULONG)length,(PUCHAR)output,20)<0) throw std::runtime_error("SHA1 hash");
 BCryptCloseAlgorithmProvider(alg,0);
}
