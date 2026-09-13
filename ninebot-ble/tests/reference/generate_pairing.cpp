#include <cstdint>
#include <cassert>
#include <string>
#include <vector>
#include <array>
#include <iostream>
#include <iomanip>
#include "NinebotCrypto.h"
using Bytes = NinebotCrypto::VarArray;
template<class T> void hex(const T& bytes){for(auto b:bytes)std::cout<<std::hex<<std::setw(2)<<std::setfill('0')<<(unsigned)b;}
void show(const char* direction,const Bytes& plain,const Bytes& cipher){std::cout<<direction<<" ";hex(plain);std::cout<<" ";hex(cipher);std::cout<<"\n";}
int main(){
 NinebotCrypto app("NBSCOOTER"), peer("NBSCOOTER");
 std::cout<<"key ";hex(app._sha1_key);std::cout<<"\n";
 Bytes request{0x5a,0xa5,0,0x3e,0x21,0x5b,0};auto cipher=app.Encrypt(request);show("tx",request,cipher);
 Bytes challenge{0x5a,0xa5,30,0x21,0x3e,0x5b,0};
 for(int i=0;i<16;i++){challenge.push_back(i);peer._random_ble_data[i]=i;}
 std::string serial="N4GSD123456789"; for(char c:serial)challenge.push_back(c);
 cipher=peer.Encrypt(challenge);show("rx",challenge,cipher);app.Decrypt(cipher);
 peer.CalcSha1Key(peer._name,peer._random_ble_data);
 Bytes setkey{0x5a,0xa5,16,0x3e,0x21,0x5c,0};for(int i=0;i<16;i++){setkey.push_back(0xf0+i);peer._random_app_data[i]=0xf0+i;}
 cipher=app.Encrypt(setkey);show("tx",setkey,cipher);peer.Decrypt(cipher);
 Bytes ack{0x5a,0xa5,0,0x21,0x3e,0x5c,1};cipher=peer.Encrypt(ack);show("rx",ack,cipher);app.Decrypt(cipher);
 peer.CalcSha1Key(peer._random_app_data,peer._random_ble_data);
 Bytes confirm{0x5a,0xa5,14,0x3e,0x21,0x5d,0};for(char c:serial)confirm.push_back(c);
 cipher=app.Encrypt(confirm);show("tx",confirm,cipher);peer.Decrypt(cipher);
 Bytes done{0x5a,0xa5,0,0x21,0x3e,0x5d,1};cipher=peer.Encrypt(done);show("rx",done,cipher);app.Decrypt(cipher);
 Bytes read{0x5a,0xa5,1,0x3e,0x20,1,0x3e,2};cipher=app.Encrypt(read);show("tx",read,cipher);
}
