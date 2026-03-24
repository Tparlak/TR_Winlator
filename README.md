# TR_Winlator Standalone Mod

Winlator'ın bu sürümü, tamamen bağımsız (Standalone) çalışacak şekilde özelleştirilmiştir. RootFS (`imagefs.txz`) APK'nın içine gömülüdür; ekstra OBB indirme gerektirmez.

## Özellikler
- **Türkçe Dil Desteği Geliştirmeleri**: Türk kullanıcılar için daha anlaşılır bir deneyim.
- **Standalone Yapı**: `imagefs.txz` (1GB RootFS) doğrudan APK içinde barındırılır. OBB dosyası veya ek indirme gerekmez.
- **Gelişmiş Performans**: GlibC v11R2 tabanlı optimizasyonlar içerir.

## Ekran Görüntüleri
![TR_Winlator Home Screen](https://via.placeholder.com/800x450?text=TR_Winlator+Home+Screen)

## Kurulum
1. [Releases](https://github.com/Tparlak/TR_Winlator/releases) sayfasından en güncel `.apk` dosyasını cihazınıza indirin ve kurun.
2. Uygulamayı ilk kez açtığınızda sistem dosyaları (`imagefs.txz`) otomatik olarak çıkartılacaktır.
3. Sağ üstteki `+` butonuna basarak yeni bir kapsayıcı (Container) oluşturun ve oynamaya başlayın!