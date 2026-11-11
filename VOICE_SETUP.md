# Sesli iletişim ve bulut sohbeti kurulumu

## Konuşma akışı

Yüze kısa dokunma mevcut mikrofon iznini ister ve varsayılan olarak Android'in kurulu konuşma tanıma servisini `tr-TR` diliyle açar. Android 7.0'daki platform servisi kendi mikrofon oturumunu yönetir ve son metni uygulamaya döndürür. `EXTRA_PREFER_OFFLINE` varsayılan olarak `false` kalır; `true` yalnızca çevrimdışı motor kullanılmasını ister ve GM 5 Plus'ta çevrimdışı SODA motoru başlatılamadığı için zorlanmaz. Servis normal modda kendi çevrimdışı/ağ kararını verebilir. Türkçe desteği, indirilmiş dil paketi ve çevrimdışı çalışma cihazdaki tanıma hizmetine bağlıdır.

Yapılandırılmış bir HTTPS PCM uç noktası varsa uygulama bunun yerine 16 kHz, mono, 16 bit PCM kaydeder; yaklaşık 900 ms sessizlikte durur ve kayıt 15 saniyeyle sınırlıdır. Bu yolda ses yalnızca bellekte tutulur, ağ tanıması bitince bellek temizlenir ve dosyaya yazılmaz. Hiçbir yolda uygulama içinde sabit örnek cümle varsayılan olarak kullanılmaz. Ham ses Android'in seçili konuşma servisi tarafından işlenebilir; bu servisin ağ ve gizlilik davranışı uygulamanın kontrolünde değildir.

Uygulama şu sırayı izler:

1. Varsayılan Android sistem tanıyıcısı, cihazın servisinin sağladığı gerçek metni döndürür. İsteğe bağlı HTTPS PCM tanıyıcısı da seçilebilir.
2. `ConversationEngine`, son sekiz konuşma mesajıyla birlikte güncel metni AI backend'ine gönderir.
3. Backend aktif sağlayıcı ve modeli seçer, kısa Türkçe yanıt üretir ve Android'e döndürür.
4. Var olan Android `TextToSpeech` yanıtı seslendirir.

Tanıma ve sohbet ağ işlemleri ana iş parçacığının dışında yürür. Yeni bir dokunma devam eden konuşmayı iptal edip yeniden dinlemeye geçer; dinleme sırasında dokunma oturumu iptal eder. Yüz, geçici olarak son tanınan cümleyi ve ardından söylenecek yanıtı tek satırlık altyazı olarak gösterir; iptal veya hatada altyazı temizlenir. Altyazı ayrıca kaydedilmez ve tam metni loglanmaz; başarılı görüşmelerin metni mevcut sekiz mesajlık yerel geçmişte tutulur.

TTS, Android `AudioManager` üzerinden geçici ses odağı alır ve konuşma tamamlandığında, iptal edildiğinde veya uygulama kapanırken odağı bırakır. Başka bir uygulama geçici ya da kalıcı ses odağı kaybına neden olursa TTS durdurulur ve kullanıcıya ayrı bir ses odağı hatası gösterilir. `RobotCore` ile `VoiceInteractionController`, mikrofon tanıma ve TTS aşamalarını sıralı tutar; tanıma tamamlanmadan yanıt sesi başlamaz. Ham ses saklanmaz; son sekiz metin mesajı uygulama özelindeki SharedPreferences alanında tutulur. Uygulama verisini silmek yerel geçmişi de siler.

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

Adres boş bırakılırsa sohbet isteği açık bir yapılandırma hatasıyla sonlanır; uygulama aynı sabit test yanıtını söylemez. `MockConversationEngine` yalnızca geliştirme kodu `ConversationEngineFactory.create(..., useMock = true)` ile açıkça seçtiğinde kullanılabilir. HTTPS her yerde kabul edilir; HTTP yalnızca `10.0.0.0/8`, `172.16.0.0/12` veya `192.168.0.0/16` özel IPv4 adreslerinde kabul edilir. Sağlayıcı/model bilgisi ve anahtarları APK içinde tutulmaz.

## Yerel backend erişimi ve bellek tercihi

Backend'de istek doğrulamasını açmak için en az 32 karakterlik rastgele bir değer üretin (`openssl rand -base64 32`) ve `backend/.env` içindeki `COMPANION_ACCESS_TOKEN` alanına kendiniz girin; ardından mevcut Docker Compose servisini yeniden başlatın. Bu uygulama `.env` dosyasını okumaz veya düzenlemez. Değişken boşken geliştirme uyumluluğu için API doğrulaması kapalıdır. Dolu olduğunda config, konuşma ve bellek uç noktaları geçerli `Authorization: Bearer` başlığı ister; `/healthz` herkese açık kalır ve sır döndürmez.

Uygulamada yüz panelinin sağ üstündeki üç noktaya veya Android Geri düğmesine dokunup aynı token'ı girin. Değer Android Keystore ile şifrelenerek cihazda saklanır; APK'ya veya `BuildConfig` içine yazılmaz. Backend anahtarı değişirse uygulamadaki kayıtlı değeri silip yenisini girin. Token Logcat'e yazılmaz.

Ayar ekranındaki “Uzun süreli hafızayı etkinleştir” izni varsayılan olarak kapalıdır. Açıldığında backend, belirli açık Türkçe ifadelerden yapılandırılmış anılar çıkarır; ilgili az sayıdaki kaydı yanıt için seçilen AI sağlayıcısına gönderebilir. Soru, varsayım ve algılanan bazı hassas bilgiler kaydedilmez. Yüz paneli ayarlarından “Hafızayı Yönet” ekranı kayıtları gösterir, değerlerini düzenler, tek kaydı siler veya tümünü onayla siler. Hafızayı kapatmak yeni kayıt ve AI retrieval'ını durdurur ama mevcut kayıtları silmez; son sekiz sohbet mesajı ayrı olarak Android'de kalır.

Yerel Docker HTTP trafiği şifrelemez; bearer token'ı ve bellek içeriğini aynı Wi-Fi ağındaki trafiği izleyen biri görebilir. SQLite dosyası da diskte şifrelenmez. Yalnızca güvendiğiniz özel ağda ve hassas olmayan tercihlerle kullanın; modemden port yönlendirmesi açmayın. Docker bellek verisi `companion-data` isimli kalıcı volume'da tutulur; `docker compose down -v` bu veriyi siler. Boş token ayarı API erişimini korumaz.

Her ses etkileşimi UUID kimliği taşır. `VoiceInteraction` Logcat kayıtları etkileşim ve HTTP istek kimliklerini, mesaj uzunluğunu, istek gövdesindeki güncel mesaj eşleşmesini ve yalnızca harfleri/uzunlukları gösteren redakte edilmiş önizlemeyi içerir. Backend logları da API anahtarı, ham ses, tam kullanıcı mesajı veya tam yanıt yazmadan istek/etkileşim kimlikleri ve sağlayıcı aşamalarını gösterir. Android ile backend, yanıtın istek ve etkileşim kimliklerinin gönderilen değerlerle eşleştiğini doğrular.

## Konuşma tanıma seçimi

Normal yapılandırmada `VoiceConfig.cloudSpeechEndpoint` boş kalır ve Android'in kurulu `SpeechRecognizer` servisi kullanılır. İstek dili varsayılan `tr-TR` olarak ayarlanır ve serbest biçimli konuşma istenir. `EXTRA_PREFER_OFFLINE` varsayılan olarak `false` bırakılır; Android bu değeri `true` yaptığında yalnızca çevrimdışı motor kullanılmasını ister. GM 5 Plus'taki önceki Logcat kaydı (`Offline recognizer - start listening` ardından `Soda recognizer failed to initialize: ConfigStatus 3`) çevrimdışı motorun yapılandırılamayıp servisin kapanmasıyla uyumlu. Varsayılan normal mod, Google servisine kendi ağ/çevrimdışı seçimini yapma fırsatı verir.

Yalnızca `tr-TR` çevrimdışı modelinin cihazda kurulu olduğu doğrulandıysa `VoiceConfig.preferOfflineSpeechRecognition = true` olarak ayarlayın. Bu kipte henüz konuşma algılanmadan `ERROR_CLIENT` veya `ERROR_SERVER_DISCONNECTED` gelirse uygulama tanıyıcıyı temizler ve ağ kullanımına izin veren **tek** kurtarma denemesi yapar. Normal kipte de bu iki hata konuşma başlamadan gelirse servis bir kez yeniden oluşturulur; sonrasında hata kullanıcıya gösterilir. Busy, timeout, no-match, izin, ağ kotası ve servis hataları ayrı açıklanır. Otomatik döngü veya boş transkript gönderimi yoktur; dil İngilizceye çevrilmez.

Tanıma oturum kimliği, servis oluşturma/başlatma, konuşma başlangıç/bitiş, tanıma sonucu, hata kodu/açıklaması, kurtarma ve kaynak temizliği `VoiceInteraction` Logcat etiketiyle yazılır; ses ve tanınan cümleler loglanmaz.

Android servisinin cihazda bulunup bulunmadığını ve gerçek Türkçe tanıma kalitesini fiziksel cihazda kontrol edin. Uçak modu veya Wi-Fi kapalı denemesi, seçili servisin çevrimdışı dil modelini gerçekten kullanabildiğini gösterir. Servis yoksa, `KONUŞMA TANIMA SERVİSİ BULUNAMADI` hatası gösterilir.

Geliştirme sırasında sabit test transkripsiyonunu açıkça seçmek için `VoiceConfig.useMockSpeechRecognition = true` ayarlayın. Bu kip yalnızca geliştirme içindir; varsayılan kapalıdır. HTTPS PCM adresi yapılandırılmışsa PCM adresi mock ayarından önceliklidir.

## İsteğe bağlı HTTPS PCM konuşma tanıma adresi

`HttpSpeechRecognizer`, `VoiceConfig.cloudSpeechEndpoint` alanına verilen HTTPS uç noktasına ham PCM gönderir. İsteğe bağlı bearer belirteci (`cloudSpeechBearerToken`) sağlayıcı anahtarı olmamalı; kısa ömürlü ve güvenli sunucu üzerinden alınmış bir erişim belirteci olmalıdır. Ayrıntılı istek sözleşmesi `SpeechRecognizer.kt` içindeki açıklamada ve aşağıda özetlenmiştir:

- `POST` gövdesi: 16 kHz, mono, 16 bit little-endian ham PCM.
- `Content-Type: audio/pcm; encoding=s16le; rate=16000; channels=1`.
- `X-Speech-Language`: varsayılan `tr-TR`; İngilizce `en-US`.
- Başarılı JSON yanıtı: `{"text":"Tanınan konuşma"}`.
- Bağlantı zaman aşımı 8 saniye, yanıt zaman aşımı 12 saniyedir.

Bu AI backend'i sohbet metinlerini işler. Varsayılan gerçek mikrofon transkripsiyonu Android sistem servisindedir; özel bir HTTPS PCM tanıma uç noktası isteğe bağlıdır. Bu uç nokta veya sağlayıcı anahtarı APK'ya sabitlenmemelidir.

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
