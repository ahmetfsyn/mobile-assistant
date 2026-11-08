# Sesli iletişim ve bulut sohbeti kurulumu

## Konuşma akışı

Yüze kısa dokunma mevcut mikrofon iznini ister ve 16 kHz, mono, 16 bit PCM kaydını başlatır. Yaklaşık 900 ms sessizlik algılandığında kayıt biter; kayıt 15 saniyeyle sınırlıdır. Ses dosyaya yazılmaz ve tanıma tamamlandığında kayıt belleği sıfırlanır.

Uygulama şu sırayı izler:

1. Var olan `SpeechRecognizer` metne çevirir. Bulut tanıma adresi ayarlı değilse geliştirme amaçlı örnek metin üretir.
2. `ConversationEngine`, son sekiz konuşma mesajıyla birlikte güncel metni AI backend'ine gönderir.
3. Backend aktif sağlayıcı ve modeli seçer, kısa Türkçe yanıt üretir ve Android'e döndürür.
4. Var olan Android `TextToSpeech` yanıtı seslendirir.

Tanıma ve sohbet ağ işlemleri ana iş parçacığının dışında yürür. Yeni bir dokunma devam eden konuşmayı iptal eder. Ham ses saklanmaz; son sekiz metin mesajı uygulama özelindeki SharedPreferences alanında tutulur. Uygulama verisini silmek yerel geçmişi de siler.

## Bulut backend'i

Bağımsız Node.js servisi `backend/` klasöründedir; üçüncü taraf npm paketi gerektirmez. Mac'te Docker Compose ile yerel kullanım ve Android'in aynı Wi-Fi üzerinden bağlanması için [`LOCAL_DOCKER_BACKEND.md`](LOCAL_DOCKER_BACKEND.md), sağlayıcı ayarları için [`backend/README.md`](backend/README.md), Oracle Cloud Ubuntu Docker dağıtımı için [`ORACLE_CLOUD_DEPLOYMENT.md`](ORACLE_CLOUD_DEPLOYMENT.md) dosyasına bakın.

Backend varsayılan olarak `gemini` sağlayıcısını ve `gemini-2.5-flash` modelini kullanır. Mac'teki Docker Compose servisi, daha hızlı yerel konuşma için açıkça `gemini-3.5-flash-lite` modelini seçer; `COMPANION_LOCAL_MODEL` ile değiştirilebilir. Yerel kullanımda anahtar `backend/.env` içinde, Oracle Cloud'da ise `/etc/masa-arkadasi/.env` içinde tutulur. Sunucudaki anahtar Docker image'a, Git'e veya Android APK'ya eklenmez. OpenAI, Anthropic ve açıkça seçilen `mock` sağlayıcıları da desteklenir. Yerel HTTP yalnızca güvenilir özel Wi-Fi ağında kullanılmalıdır; internetten erişim veya modem port yönlendirmesi için değildir.

Android her konuşmada `/v1/config` üzerinden etkin sağlayıcı/model bilgisini alır ve metni `/v1/conversations` adresine yollar. Sağlayıcı ve model değişiklikleri backend ortamı güncellenip servis yeniden başlatıldığında APK'yı yeniden derlemeden geçerli olur.

## Android backend adresi

İnternete açık dağıtımlarda HTTPS adresi kullanın. Yerel Mac geliştirmede aynı özel Wi-Fi ağına bağlı telefon için `http://MAC_LOCAL_IP:3000` adresini kullanın; `MAC_LOCAL_IP` yerine Mac'in Wi-Fi IPv4 adresini yazın. HTTP sohbet metinlerini şifrelemez; yalnızca güvendiğiniz yerel ağda kullanın ve modemden port yönlendirmesi açmayın. Android backend adresi Gradle özelliği veya `COMPANION_BACKEND_URL` ortam değişkeniyle alır; kaynak koda geçici bir IP yazılmaz ve adreste API anahtarı bulunmaz:

```sh
./gradlew assembleDebug -PcompanionBackendUrl=http://MAC_LOCAL_IP:3000
```

Alternatif olarak `COMPANION_BACKEND_URL` ortam değişkeni kullanılabilir:

```sh
COMPANION_BACKEND_URL=http://MAC_LOCAL_IP:3000 ./gradlew assembleDebug
```

Adres boş bırakılırsa `MockConversationEngine` mevcut yerel test yanıtını kullanır. HTTPS her yerde kabul edilir; HTTP yalnızca `10.0.0.0/8`, `172.16.0.0/12` veya `192.168.0.0/16` özel IPv4 adreslerinde kabul edilir. Sağlayıcı/model bilgisi ve anahtarları APK içinde tutulmaz.

## Mevcut bulut konuşma tanıma adresi

Var olan `HttpSpeechRecognizer`, `VoiceConfig.cloudSpeechEndpoint` alanına verilen HTTPS uç noktasına ham PCM gönderir. İsteğe bağlı bearer belirteci (`cloudSpeechBearerToken`) sağlayıcı anahtarı olmamalı; kısa ömürlü ve güvenli sunucu üzerinden alınmış bir erişim belirteci olmalıdır. Varsayılan boş adres `MockSpeechRecognizer` kullanır. Ayrıntılı istek sözleşmesi `SpeechRecognizer.kt` içindeki açıklamada ve aşağıda özetlenmiştir:

- `POST` gövdesi: 16 kHz, mono, 16 bit little-endian ham PCM.
- `Content-Type: audio/pcm; encoding=s16le; rate=16000; channels=1`.
- `X-Speech-Language`: varsayılan `tr-TR`; İngilizce `en-US`.
- Başarılı JSON yanıtı: `{"text":"Tanınan konuşma"}`.
- Bağlantı zaman aşımı 8 saniye, yanıt zaman aşımı 12 saniyedir.

Bu AI backend'i sohbet metinlerini işler; mevcut konuşma tanıma adaptörünün yerini almaz. Gerçek mikrofon transkripsiyonu için ayrıca güvenilir HTTPS tanıma adresi gerekir. Bu adres veya bir sağlayıcı anahtarı APK'ya sabitlenmemelidir.

## Dil ve sorun giderme

Varsayılan dil `tr-TR`'dir; Android yalnızca uygulama açıkça İngilizceye geçirildiğinde `en-US` kullanır. Türkçe/İngilizce TTS verisi cihazda yüklü değilse uygulama başka dile geçmek yerine Türkçe hata gösterir.

- `HTTP ADRESİ ÖZEL LAN IP'Sİ OLMALI`: yerel kullanımda özel Wi-Fi IPv4 adresi girin.
- `BULUT SUNUCU HTTPS OLMALI`: genel dağıtımda HTTPS adresi verin.
- `BULUT AI KİMLİK BİLGİLERİ YAPILANDIRILMAMIŞ`: backend ortamına seçili sağlayıcının API anahtarını ekleyin.
- `SEÇİLEN AI MODELİ DESTEKLENMİYOR`: sağlayıcı hesabında kullanılabilen bir `AI_MODEL` seçin.
- `AI KOTASI TÜKENDİ`: Google AI Studio projesinin kota, kullanım sınırı veya faturalandırma durumunu kontrol edin.
- `AI SAĞLAYICISI ZAMAN AŞIMINA UĞRADI`: backend Gemini yanıtını belirtilen süre içinde alamadı; Docker günlüklerindeki `provider_timeout` kodunu ve aynı istek kimliğini kontrol edin.
- `AI YANITI YARIM KALDI; TEKRAR DENE`: Gemini çıktı sınırına ulaştı; yarım yanıt seslendirilmez.
- `İNTERNET BAĞLANTISI YOK` veya zaman aşımı: telefon bağlantısını, servis dağıtımını ve servis sağlayıcı durumunu kontrol edin.
- AI servisi `mock` görünüyorsa backend `AI_PROVIDER` değerini değiştirip servisi yeniden başlatın.

Gerçek sağlayıcı anahtarı olmadan yapılan backend testleri sadece sahte yanıtları ve adaptör sözleşmelerini doğrular; gerçek AI üretimini doğrulamaz. `gemini-2.5-flash` hesaba açık değilse desteklenen bir model adını backend'deki `AI_MODEL` değerine girin; uygulama sessizce başka modele geçmez.
