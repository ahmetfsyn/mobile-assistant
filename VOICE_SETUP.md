# Sesli iletişim kurulumu

## Varsayılan geliştirme modu

Yüze kısa dokunma, mikrofon iznini ister ve ardından 16 kHz, mono, 16 bit PCM kaydını başlatır. Yaklaşık 900 ms sessizlik algılandığında kayıt biter; kayıt 15 saniyeyle sınırlıdır. Ses dosyaya yazılmaz. PCM verisi tanıma tamamlanınca bellekten sıfırlanır.

Bir konuşma tanıma adresi tanımlanmadığında `MockSpeechRecognizer` kullanılır. Bu mod örnek bir Türkçe konuşma metni üretir ve ayarlanabilir deneme yanıtını Android'in yerel TextToSpeech motoruyla seslendirir.

## Bulut konuşma tanıma

`VoiceConfig.cloudSpeechEndpoint` alanına sağlayıcınızın HTTPS adresini, erişim belirteci kullanılıyorsa `VoiceConfig.cloudSpeechBearerToken` alanına belirteci aktarın. Gerçek uygulamada kısa ömürlü belirteci güvenli bir sunucu üzerinden alın; API anahtarı uygulama kaynak koduna veya APK'ya eklenmemelidir.

Uç nokta şu sözleşmeyi kullanır:

- `POST` gövdesi: ham PCM, 16 kHz, mono, 16 bit little-endian.
- `Content-Type`: `audio/pcm; encoding=s16le; rate=16000; channels=1`.
- `X-Speech-Language`: varsayılan `tr-TR`; İngilizce seçildiğinde `en-US`.
- İsteğe bağlı `Authorization: Bearer <token>` başlığı.
- Başarılı yanıt: `{"text":"Tanınan konuşma"}`.

Bağlantı zaman aşımı 8 saniye, yanıt zaman aşımı 12 saniyedir. Ağ isteği ana iş parçacığının dışında yapılır. AndroidManifest içindeki `INTERNET` izni gerekir.

## Dil ve yanıt ayarı

Varsayılan dil `VoiceConfig.language = VoiceLanguage.TURKISH` değeridir. İngilizce desteğini açıkça seçmek için `VoiceLanguage.ENGLISH` atanabilir. Deneme konuşmaları `VoiceConfig.turkishTestResponse` ve `VoiceConfig.englishTestResponse` alanlarından değiştirilebilir.

Ses çıkışı Android'in yerel `TextToSpeech` motorunu kullanır. Türkçe veya İngilizce ses verisi cihazda yüklü değilse uygulama başka bir dile kendiliğinden geçmez; Türkçe hata durumu gösterir. Bulut TTS daha sonra `SpeechOutput` arayüzü üzerinden eklenebilir.
