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

Bağımsız Node.js servisi `backend/` klasöründedir; üçüncü taraf npm paketi gerektirmez. Yerel test ve sağlayıcı ayarları için [`backend/README.md`](backend/README.md), Oracle Cloud Ubuntu Docker dağıtımı için [`ORACLE_CLOUD_DEPLOYMENT.md`](ORACLE_CLOUD_DEPLOYMENT.md) dosyasına bakın.

Backend varsayılan olarak `gemini` sağlayıcısını ve `gemini-2.5-flash` modelini kullanır. Yerel kullanımda anahtar `backend/.env` içinde, Oracle Cloud'da ise `/etc/masa-arkadasi/.env` içinde tutulur. Sunucudaki anahtar Docker image'a, Git'e veya Android APK'ya eklenmez. OpenAI, Anthropic ve açıkça seçilen `mock` sağlayıcıları da desteklenir. Kurulum, HTTPS, gerçek API isteği, model değiştirme ve kota bilgileri için [`backend/README.md`](backend/README.md) ve [`ORACLE_CLOUD_DEPLOYMENT.md`](ORACLE_CLOUD_DEPLOYMENT.md) dosyalarına bakın.

Android her konuşmada `/v1/config` üzerinden etkin sağlayıcı/model bilgisini alır ve metni `/v1/conversations` adresine yollar. Sağlayıcı ve model değişiklikleri backend ortamı güncellenip servis yeniden başlatıldığında APK'yı yeniden derlemeden geçerli olur.

## Android backend adresi

Backend'in Caddy üzerinden HTTPS kök adresini Android derlemesine verin. Proje bu adresi Gradle özelliği veya `COMPANION_BACKEND_URL` ortam değişkeniyle alır; kaynak koda geçici bir sağlayıcı adresi yazılmaz. Adreste API anahtarı veya başka sır bulunmamalıdır:

```sh
./gradlew assembleDebug -PcompanionBackendUrl=https://companion.example.com
```

Alternatif olarak `COMPANION_BACKEND_URL` ortam değişkeni kullanılabilir:

```sh
COMPANION_BACKEND_URL=https://companion.example.com ./gradlew assembleDebug
```

Adres boş bırakılırsa `MockConversationEngine` mevcut yerel test yanıtını kullanır. HTTPS dışında bir adres reddedilir. Sağlayıcı/model bilgisi ve anahtarları APK içinde tutulmaz.

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

- `BULUT SUNUCU ADRESİ HTTPS OLMALI`: HTTPS kök adresi verin.
- `BULUT AI KİMLİK BİLGİLERİ YAPILANDIRILMAMIŞ`: backend ortamına seçili sağlayıcının API anahtarını ekleyin.
- `SEÇİLEN AI MODELİ DESTEKLENMİYOR`: sağlayıcı hesabında kullanılabilen bir `AI_MODEL` seçin.
- `AI KOTASI TÜKENDİ`: Google AI Studio projesinin kota, kullanım sınırı veya faturalandırma durumunu kontrol edin.
- `İNTERNET BAĞLANTISI YOK` veya zaman aşımı: telefon bağlantısını, servis dağıtımını ve servis sağlayıcı durumunu kontrol edin.
- AI servisi `mock` görünüyorsa backend `AI_PROVIDER` değerini değiştirip servisi yeniden başlatın.

Gerçek sağlayıcı anahtarı olmadan yapılan backend testleri sadece sahte yanıtları ve adaptör sözleşmelerini doğrular; gerçek AI üretimini doğrulamaz. `gemini-2.5-flash` hesaba açık değilse desteklenen bir model adını backend'deki `AI_MODEL` değerine girin; uygulama sessizce başka modele geçmez.
