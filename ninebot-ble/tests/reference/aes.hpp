#include <windows.h>
#include <bcrypt.h>
#include <stdexcept>
#include <cstring>
struct AES_ctx { unsigned char key[16]; };
inline void AES_init_ctx(AES_ctx* ctx, const unsigned char* key) { memcpy(ctx->key,key,16); }
inline void AES_ECB_encrypt(const AES_ctx* ctx, unsigned char* data) {
 BCRYPT_ALG_HANDLE alg; BCRYPT_KEY_HANDLE key; ULONG count;
 if(BCryptOpenAlgorithmProvider(&alg,BCRYPT_AES_ALGORITHM,nullptr,0)<0) throw std::runtime_error("AES provider");
 if(BCryptSetProperty(alg,BCRYPT_CHAINING_MODE,(PUCHAR)BCRYPT_CHAIN_MODE_ECB,sizeof(BCRYPT_CHAIN_MODE_ECB),0)<0) throw std::runtime_error("AES mode");
 if(BCryptGenerateSymmetricKey(alg,&key,nullptr,0,(PUCHAR)ctx->key,16,0)<0) throw std::runtime_error("AES key");
 unsigned char output[16]; if(BCryptEncrypt(key,data,16,nullptr,nullptr,0,output,16,&count,0)<0) throw std::runtime_error("AES encrypt");
 memcpy(data,output,16); BCryptDestroyKey(key); BCryptCloseAlgorithmProvider(alg,0);
}
